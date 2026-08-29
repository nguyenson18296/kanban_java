package com.kanban.common.validation;

import com.kanban.common.exception.BadRequestException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Port of Nest's global {@code ValidationPipe({ whitelist, forbidNonWhitelisted,
 * transform })} on top of class-validator/class-transformer semantics:
 * <ul>
 * <li>unknown keys → {@code "property <key> should not exist"} (reported first)</li>
 * <li>per property, in declaration order; constraints reported bottom-up like
 *     TypeScript decorators (i.e. reverse of the Java annotation order)</li>
 * <li>{@code @IsOptional} skips a property whose value is null/absent</li>
 * <li>{@code each} constraints are skipped when the value is not an array</li>
 * <li>{@code @TypeNumber} / {@code @TransformWith} run only for present keys</li>
 * </ul>
 * On failure a {@link BadRequestException} with the flat message array is thrown.
 */
public final class ClassValidator {
  private ClassValidator() {}

  private static final String UNKNOWN_VALUE = "an unknown value was passed to the validate function";

  /** Validate + transform a plain map (JSON object / query map) into {@code type}. */
  public static <T extends ValidatedDto> T validate(Class<T> type, Map<String, Object> plain) {
    if (plain == null) {
      plain = Collections.emptyMap();
    }
    List<Field> fields = declaredFields(type);
    Map<String, Field> byName = new LinkedHashMap<>();
    for (Field f : fields) {
      byName.put(f.getName(), f);
    }

    T instance;
    try {
      var ctor = type.getDeclaredConstructor();
      ctor.setAccessible(true);
      instance = ctor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("DTO " + type.getName() + " needs a no-arg constructor", e);
    }

    List<String> messages = new ArrayList<>();
    // 1) whitelist: unknown properties first, in payload order
    for (String key : plain.keySet()) {
      if (!byName.containsKey(key)) {
        messages.add("property " + key + " should not exist");
      }
    }

    // 2) transform + validate each declared property
    for (Field field : fields) {
      String name = field.getName();
      boolean present = plain.containsKey(name);
      Object value;
      if (present) {
        value = applyTransforms(field, plain.get(name));
        if (value == JsValues.UNDEFINED) {
          // a @Transform returned undefined → behaves exactly like an absent key
          present = false;
          value = null;
        } else {
          instance.markPresent(name);
        }
      } else {
        value = null; // undefined
      }
      List<Annotation> constraints = constraintAnnotations(field);
      boolean optional = field.isAnnotationPresent(IsOptional.class);
      if (!(optional && value == null)) {
        // class-validator reports constraints bottom-up
        List<Annotation> ordered = new ArrayList<>(constraints);
        Collections.reverse(ordered);
        for (Annotation a : ordered) {
          String msg = check(a, name, value);
          if (msg != null) {
            messages.add(msg);
          }
        }
      }
      if (present && messages.isEmpty()) {
        assign(instance, field, value);
      } else if (present) {
        // still assign what we can so partially valid state isn't needed; errors thrown below
        try {
          assign(instance, field, value);
        } catch (RuntimeException ignored) {
          // type mismatch is already reported as a validation error
        }
      }
    }

    if (!messages.isEmpty()) {
      throw new BadRequestException(messages);
    }
    return instance;
  }

  /** Nest: arrays / non-objects reach class-validator without metadata. */
  public static BadRequestException unknownValue() {
    return new BadRequestException(List.of(UNKNOWN_VALUE));
  }

  // ---------------------------------------------------------------------------
  // transforms
  // ---------------------------------------------------------------------------

  private static Object applyTransforms(Field field, Object value) {
    Object out = value;
    if (field.isAnnotationPresent(TypeNumber.class)) {
      if (out instanceof List<?> list) {
        List<Object> converted = new ArrayList<>(list.size());
        for (Object item : list) {
          converted.add(JsValues.jsNumber(item));
        }
        out = converted;
      } else {
        out = JsValues.jsNumber(out);
      }
    }
    TransformWith tw = field.getAnnotation(TransformWith.class);
    if (tw != null) {
      try {
        ValueTransformer t = tw.value().getDeclaredConstructor().newInstance();
        out = t.transform(out);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Cannot instantiate transformer " + tw.value(), e);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // constraint checks — messages copied from class-validator defaults
  // ---------------------------------------------------------------------------

  private static String check(Annotation a, String prop, Object value) {
    if (a instanceof IsString) {
      return value instanceof String ? null : prop + " must be a string";
    }
    if (a instanceof IsEmail) {
      return value instanceof String s && EmailValidator.isEmail(s) ? null : prop + " must be an email";
    }
    if (a instanceof IsBoolean) {
      return value instanceof Boolean ? null : prop + " must be a boolean value";
    }
    if (a instanceof IsDate) {
      return value instanceof Instant ? null : prop + " must be a Date instance";
    }
    if (a instanceof IsArray) {
      return value instanceof List<?> ? null : prop + " must be an array";
    }
    if (a instanceof IsNotEmpty) {
      boolean ok = value != null && !"".equals(value);
      return ok ? null : prop + " should not be empty";
    }
    if (a instanceof ArrayNotEmpty) {
      boolean ok = value instanceof List<?> l && !l.isEmpty();
      return ok ? null : prop + " should not be empty";
    }
    if (a instanceof ArrayMinSize min) {
      boolean ok = value instanceof List<?> l && l.size() >= min.value();
      return ok ? null : prop + " must contain at least " + min.value() + " elements";
    }
    if (a instanceof ArrayMaxSize max) {
      boolean ok = value instanceof List<?> l && l.size() <= max.value();
      return ok ? null : prop + " must contain no more than " + max.value() + " elements";
    }
    if (a instanceof MinLength min) {
      boolean ok = value instanceof String s && s.codePointCount(0, s.length()) >= min.value();
      return ok ? null : prop + " must be longer than or equal to " + min.value() + " characters";
    }
    if (a instanceof MaxLength max) {
      boolean ok = value instanceof String s && s.codePointCount(0, s.length()) <= max.value();
      return ok ? null : prop + " must be shorter than or equal to " + max.value() + " characters";
    }
    if (a instanceof Matches m) {
      boolean ok = value instanceof String s && Pattern.compile(m.value()).matcher(s).find();
      if (ok) {
        return null;
      }
      return m.message().isEmpty() ? prop + " must match " + m.value() + " regular expression" : m.message();
    }
    if (a instanceof Min min) {
      boolean ok = JsValues.isNumber(value) && JsValues.toDouble(value) >= min.value();
      return ok ? null : prop + " must not be less than " + formatNumber(min.value());
    }
    if (a instanceof Max max) {
      boolean ok = JsValues.isNumber(value) && JsValues.toDouble(value) <= max.value();
      return ok ? null : prop + " must not be greater than " + formatNumber(max.value());
    }
    if (a instanceof IsInt isInt) {
      if (isInt.each()) {
        if (!(value instanceof List<?> l)) {
          return null;
        }
        for (Object item : l) {
          if (!JsValues.isInteger(item)) {
            return "each value in " + prop + " must be an integer number";
          }
        }
        return null;
      }
      return JsValues.isInteger(value) ? null : prop + " must be an integer number";
    }
    if (a instanceof IsUUID isUuid) {
      if (isUuid.each()) {
        if (!(value instanceof List<?> l)) {
          return null;
        }
        for (Object item : l) {
          if (!(item instanceof String s && UuidPatterns.isUuid(s, isUuid.version()))) {
            return "each value in " + prop + " must be a UUID";
          }
        }
        return null;
      }
      boolean ok = value instanceof String s && UuidPatterns.isUuid(s, isUuid.version());
      return ok ? null : prop + " must be a UUID";
    }
    if (a instanceof IsEnum isEnum) {
      List<String> values = new ArrayList<>();
      for (Enum<?> e : isEnum.value().getEnumConstants()) {
        values.add(((WireEnum) e).value());
      }
      boolean ok = value instanceof String s && values.contains(s);
      return ok ? null : prop + " must be one of the following values: " + String.join(", ", values);
    }
    return null;
  }

  private static String formatNumber(double d) {
    return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
  }

  // ---------------------------------------------------------------------------
  // reflection helpers
  // ---------------------------------------------------------------------------

  private static List<Field> declaredFields(Class<?> type) {
    List<Field> out = new ArrayList<>();
    List<Class<?>> chain = new ArrayList<>();
    for (Class<?> c = type; c != null && c != ValidatedDto.class && c != Object.class; c = c.getSuperclass()) {
      chain.add(0, c);
    }
    for (Class<?> c : chain) {
      for (Field f : c.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
          continue;
        }
        if (constraintAnnotations(f).isEmpty() && !f.isAnnotationPresent(IsOptional.class)) {
          continue;
        }
        f.setAccessible(true);
        out.add(f);
      }
    }
    return out;
  }

  private static final List<Class<? extends Annotation>> CONSTRAINTS = Arrays.asList(
      IsString.class, IsEmail.class, IsBoolean.class, IsDate.class, IsArray.class, IsNotEmpty.class,
      ArrayNotEmpty.class, ArrayMinSize.class, ArrayMaxSize.class, MinLength.class, MaxLength.class,
      Matches.class, Min.class, Max.class, IsInt.class, IsUUID.class, IsEnum.class);

  private static List<Annotation> constraintAnnotations(Field f) {
    List<Annotation> out = new ArrayList<>();
    for (Annotation a : f.getDeclaredAnnotations()) {
      if (CONSTRAINTS.contains(a.annotationType())) {
        out.add(a);
      }
    }
    return out;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void assign(Object instance, Field field, Object value) {
    Class<?> ft = field.getType();
    Object coerced = value;
    if (value != null) {
      if (ft == Integer.class || ft == int.class) {
        coerced = ((Number) value).intValue();
      } else if (ft == Long.class || ft == long.class) {
        coerced = ((Number) value).longValue();
      } else if (ft == Double.class || ft == double.class) {
        coerced = ((Number) value).doubleValue();
      } else if (ft.isEnum() && value instanceof String) {
        coerced = WireEnum.fromValue((Class) ft, value);
      } else if (ft == List.class && value instanceof List<?> list) {
        // Integer lists: coerce Number elements
        var generic = field.getGenericType();
        if (generic instanceof java.lang.reflect.ParameterizedType pt
            && pt.getActualTypeArguments()[0] == Integer.class) {
          List<Object> ints = new ArrayList<>();
          for (Object o : list) {
            ints.add(((Number) o).intValue());
          }
          coerced = ints;
        }
      }
    }
    try {
      field.set(instance, coerced);
    } catch (IllegalAccessException | IllegalArgumentException e) {
      throw new IllegalStateException("Cannot assign " + field.getName(), e);
    }
  }
}
