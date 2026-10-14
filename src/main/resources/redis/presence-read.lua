-- Per user: live socket count (score > now) and changed_at (0 when the user never connected).
-- KEYS: per user: sockets ZSET, status HASH.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local live = string.format('(%d', now)
local result = {}
for k = 1, #KEYS, 2 do
  table.insert(result, redis.call('ZCOUNT', KEYS[k], live, '+inf'))
  table.insert(result, tonumber(redis.call('HGET', KEYS[k + 1], 'changed_at') or 0))
end
return result
