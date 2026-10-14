-- Upsert sockets with score = expiresAt, so a heartbeat also re-registers them after data loss.
-- KEYS[1] = expiry index; then per user: sockets ZSET, status HASH.
-- ARGV[1] = TTL ms; then per user: user id, socket count, socket ids.
-- Returns user id, live socket count, changed_at for each user that went online.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local expires = now + tonumber(ARGV[1])
local result = {}
local a = 2
for k = 2, #KEYS, 2 do
  local sockets, status, user = KEYS[k], KEYS[k + 1], ARGV[a]
  local n = tonumber(ARGV[a + 1])
  for i = a + 2, a + 1 + n do
    redis.call('ZADD', sockets, expires, ARGV[i])
  end
  a = a + 2 + n
  redis.call('ZREMRANGEBYSCORE', sockets, '-inf', now)
  local first = redis.call('ZRANGE', sockets, 0, 0, 'WITHSCORES')
  redis.call('ZADD', KEYS[1], first[2], user)
  if redis.call('HGET', status, 'online') ~= '1' then
    redis.call('HSET', status, 'online', 1, 'changed_at', now)
    table.insert(result, user)
    table.insert(result, redis.call('ZCARD', sockets))
    table.insert(result, now)
  end
end
return result
