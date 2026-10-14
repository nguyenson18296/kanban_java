-- User ids whose earliest socket expiry has passed, by Redis's clock.
-- KEYS[1] = expiry index; ARGV[1] = max ids to return.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
return redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, ARGV[1])
