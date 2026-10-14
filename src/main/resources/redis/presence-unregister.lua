-- Remove sockets and purge expired ones; the user goes offline only when none is left.
-- KEYS[1] = expiry index; then per user: sockets ZSET, status HASH.
-- ARGV: per user: user id, socket count, socket ids (count 0 only purges expired sockets).
-- Returns user id, 0, changed_at for each user that went offline.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local result = {}
local a = 1
for k = 2, #KEYS, 2 do
  local sockets, status, user = KEYS[k], KEYS[k + 1], ARGV[a]
  local n = tonumber(ARGV[a + 1])
  for i = a + 2, a + 1 + n do
    redis.call('ZREM', sockets, ARGV[i])
  end
  a = a + 2 + n
  redis.call('ZREMRANGEBYSCORE', sockets, '-inf', now)
  local first = redis.call('ZRANGE', sockets, 0, 0, 'WITHSCORES')
  if first[2] then
    redis.call('ZADD', KEYS[1], first[2], user)
  else
    redis.call('ZREM', KEYS[1], user)
    if redis.call('HGET', status, 'online') == '1' then
      redis.call('HSET', status, 'online', 0, 'changed_at', now)
      table.insert(result, user)
      table.insert(result, 0)
      table.insert(result, now)
    end
  end
end
return result
