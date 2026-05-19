package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getByIconList() {
        // 从redis中查询
        String key = CACHE_SHOP_TYPE_KEY;
        List<String> shopTypeList = new ArrayList<>();
        // range()中的-1表示最后一位
        // shopTypeList中存放的数据是[{...},{...},{...}] 一个列表有一个json对象
        shopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 判断是否缓存了
        // 中了返回
        if(CollUtil.isNotEmpty(shopTypeList)){
            List<ShopType> typeList = new ArrayList<>();
            if (shopTypeList != null) {
                for (String s : shopTypeList) {
                    ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                    typeList.add(shopType);
                }
            }
            System.out.println("redis");
            return Result.ok(typeList);
        }
        // redis若未命中数据，从数据库中获取，根据shopType对象的sort属性排序后存入typeList
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 数据库中不存在直接返回错误
        if (CollUtil.isEmpty(typeList)){
            return Result.fail("不存在分类");
        }
        for (ShopType s : typeList) {
            String jsonStr = JSONUtil.toJsonStr(s);
            if (shopTypeList != null) {
                shopTypeList.add(jsonStr);
            }
        }
        // 存在直接添加进缓存
        if (shopTypeList != null) {
            System.out.println("mysql");
            stringRedisTemplate.opsForList().rightPushAll(key, shopTypeList);
        }
        return Result.ok(typeList);
    }
}
