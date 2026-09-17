if redis.call('hget',KEYS[3],ARGV[2])~='HELD' then return 0 end
if redis.call('exists',KEYS[1])==0 then return -1 end
redis.call('hset',KEYS[3],ARGV[2],'RELEASED')
redis.call('incr',KEYS[1])
if redis.call('hget',KEYS[2],ARGV[1])==ARGV[2] then redis.call('hdel',KEYS[2],ARGV[1]) end
return 1
