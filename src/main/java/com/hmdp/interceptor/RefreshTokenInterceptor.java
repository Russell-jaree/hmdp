package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception{

        //请求头上获取token
        String token = request.getHeader(RedisConstants.REQUEST_HEADER_TOKEN);
        if (StrUtil.isBlank(token)){ //StrUtil 字符串工具类 .isBlank判断是否空白
            // 没有token说明是游客，直接放行，交给LoginInterceptor去判断该接口要不要登录
            return true;
        }

        //获取redis的用户
        String tokenKey = RedisConstants.LOGIN_USER_KEY+token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);

        //判断是否存在
        if(userMap .isEmpty()){
            // token无效或已过期，同样放行，不在这里拦
            System.out.println("登录用户不存在");
            return true;
        }

        //map转成dto去存threadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //存息到threadLocal
        UserHolder.saveUser(userDTO);

        //刷新token时间
        // expire 给一个已经存在的 key 设置“倒计时”，时间一到 Redis 自动把这个 key 删掉。它本身不存任何数据。
        //所以既能设置倒计时，也能重置倒计时，比如一开始设定30分钟，10；10时又运行一遍就变成了10；40结束了
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        return true;
    }

    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserHolder.removeUser();
    }

}
