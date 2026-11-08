package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getTypeList() {

        //从redis查
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String shopTypeStr = stringRedisTemplate.opsForValue().get(key);

        //查到就返回给前端
        if (StrUtil.isNotBlank(shopTypeStr)){
            //JSONUtil
            List<ShopType> shopType  = new ArrayList<>();
            String[] spilt = shopTypeStr.split(";");
            Arrays.stream(spilt).forEach(s -> {
                shopType.add(JSONUtil.toBean(s,ShopType.class));
            });
            return Result.ok(shopType);
        }

        //查不到就去数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        //存redis
        List<String> list  = new ArrayList<>();
        StringBuilder jsonStr = new StringBuilder();
        typeList.forEach(s ->{
            jsonStr.append(JSONUtil.toJsonStr(s)+";");
        });

        stringRedisTemplate.opsForValue().set(key,jsonStr.toString());

        return Result.ok(typeList);
    }
}
