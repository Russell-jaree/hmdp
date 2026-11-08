package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.MessageConstant;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //ExecutorService 线程池的接口类型，newFixedThreadPool固定x个线程的池子
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result getshopById(Long id) {

        //从redis查缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(shopJson)){
            //存在就返回
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            //JSONUtil.toBean JSON 字符串转 Bean 对象
            //JSONUtil.toJsonStr Bean转json
            //JSONUtil.toList json数组转list《bean》
            return Result.ok(shop);
        }
        //没有查到数据，获取互斥锁

        //判断是否为空值
        if (shopJson != null){
            //和StrUtil.isNotBlank(shopJson) 区别就是，！=null，可以为“”，“ ”这种，上面的只会通过正常串。
            return Result.fail(MessageConstant.SHOP_NOT_EXIST);
        }

        //没有就查数据库，然后存到redis
        Shop shop = query().eq("id", id).one();
        if (shop == null){
            //数据库为空，也存到redis去，设置一个较短的ttl
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail(MessageConstant.SHOP_NOT_EXIST);
        }
        stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //释放互斥锁

        return Result.ok(shop);
    }

    //缓存穿透
    //逻辑过期方法
    public Shop queryWithLogicalExpire(Long id) {

        //从redis查缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isBlank(shopJson)){
            //存在就返回
            return null;
        }

        //命中，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //没过期，直接返回店铺信息
            return shop;
        }
        //已经过期需要缓存重建
        //获得互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //有锁
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    log.error("缓存重建失败",e);
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        return shop;
    }

    //互斥锁
    public Shop queryWithMutex(Long id) {

        //从redis查缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(shopJson)){
            //存在就返回
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        //判断是否为空值
        if (shopJson != null){
            return null ;
        }

        //实现缓存重建
        //没有查到数据，获取互斥锁

        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;

        Shop shop = null;

        try {
            boolean isLock = tryLock(lockKey);

            //有其他人占用，锁住
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(id) ;
            }

            //不存在，去查数据库
            shop = getById(id);

            //查到空值了，防止穿透
            if (shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //正常值就直接存
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }

    /**
     * 更新店铺
     * @param shop
     * @return
     */
    @Override
    public Result update(Shop shop) {

        //先改数据库
        Long id = shop.getId();
        if (id == null){
            return Result.fail(MessageConstant.SHOP_ID_IS_NULL);
        }

        updateById(shop);
        //删除redis
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);

        return null;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    private void saveShop2Redis(Long id,Long expiredSeconds){
        Shop shop = getById(id);

        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSeconds));

        //写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id ,JSONUtil.toJsonStr(redisData));

    }

}
