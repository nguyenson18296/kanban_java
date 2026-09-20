-- One atomic read/decide/update; the first request starts the window.
-- Return 0 when allowed, otherwise milliseconds until the existing window ends.
local count = tonumber(redis.call('GET', KEYS[1]))
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
if not count then
  redis.call('SET', KEYS[1], 1, 'PX', window)
  return 0
end

local ttl = redis.call('PTTL', KEYS[1])
-- Repair a counter without expiry, retaining its quota usage.
if ttl < 0 then
  redis.call('PEXPIRE', KEYS[1], window)
  ttl = window
end
if count < limit then
  redis.call('INCR', KEYS[1])
  return 0
end
-- Denials neither grow the counter nor extend the window.
return math.max(ttl, 1)
