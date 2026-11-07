package com.hmdp.service.impl;

import ch.qos.logback.classic.pattern.MessageConverter;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.MessageConstant;
import com.hmdp.constant.SessionConstant;
import com.hmdp.constant.UserConstant;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.hmdp.utils.RegexUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {

        //校验手机号是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail(MessageConstant.PHONE_IS_ILLEGAL);
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);

        //保存到redis，以手机号为key
        //session.setAttribute(SessionConstant.CODE,code); 不用session
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES); //设置一个有效期2min

        //TODO 发送验证码,需要连接第三方服务
        log.info("发送验证码成功，验证码为：{}",code);
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();
        //校验手机号是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail(MessageConstant.PHONE_IS_ILLEGAL);
        }

        //验证code是否一致
        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY+phone);
                if (code == null || !code.toString().equals(loginForm.getCode())) {
            return Result.fail(MessageConstant.CODE_IS_ERROR);
        }

        //查询数据库中是否有该用户
        User user = query().eq("phone", phone).one();

        //没有就新建一个到数据库，然后存到session
        if (user == null){
            user = createUserWithPhone(phone);
        }

        //保存到redis
        //随机生成token，作为登录令牌
        //将user转为hash来存储
        String token = UUID.randomUUID().toString().toUpperCase(); //toUpperCase()是改成全大写的意思
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user,userDTO);

        //把been转换成map，但是id是long，hash只能存字符串,所以要改类型，
        // icon可能是null，但是redis不许存空，所以要跳过
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //CopyOptions.create()是允许拷贝/转换规则配置对象，setIgnoreNullValue(true)，值为null的不拷贝
        //setFieldValueEditor对每个字段的值做自定义加工，
        // (fieldName,fieldValue) -> fieldValue.toString())所有的value都设置成string

        //存到redis
        String tokenKey  = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置token有效期
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.SECONDS);

        return Result.ok(token);
    }

    /**
     * 根据手机号创建新用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = new User();

        user.setPhone(phone);
        user.setCreateTime(LocalDateTime.now());
        user.setNickName(UserConstant.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10));

        save(user);
        return user;
    }
}
