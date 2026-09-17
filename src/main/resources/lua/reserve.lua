-- KEYS share ticket hash tag. ARGV: user,reservation,now,saleStart,saleEnd,status
local previous=redis.call('hget',KEYS[3],ARGV[2])
if previous then if previous=='HELD' then return 'OK' else return previous end end
if ARGV[6]~='ON_SALE' then return 'SALE_PAUSED' end
if tonumber(ARGV[3])<tonumber(ARGV[4]) then return 'SALE_NOT_STARTED' end
if tonumber(ARGV[3])>=tonumber(ARGV[5]) then return 'SALE_ENDED' end
local stock=redis.call('get',KEYS[1])
if not stock then return 'STOCK_NOT_READY' end
if redis.call('hexists',KEYS[2],ARGV[1])==1 then return 'PURCHASE_LIMIT' end
if tonumber(stock)<=0 then return 'SOLD_OUT' end
redis.call('decr',KEYS[1])
redis.call('hset',KEYS[2],ARGV[1],ARGV[2])
redis.call('hset',KEYS[3],ARGV[2],'HELD')
return 'OK'
