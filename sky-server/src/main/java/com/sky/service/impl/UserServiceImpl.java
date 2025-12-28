package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.mapper.UserMapper;
import com.sky.properties.WeChatProperties;
import com.sky.service.UserService;
import com.sky.utils.HttpClientUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class UserServiceImpl implements UserService {
    //微信服务接口地址
    public static final String WX_LOGIN="https://api.weixin.qq.com/sns/jscode2session";

    @Autowired
    private WeChatProperties weChatProperties;
    @Autowired
    private UserMapper userMapper;

    /**
     * 用户登录
     * @param userLoginDTO
     * @return
     */
    @Override
    public User wxLogin(UserLoginDTO userLoginDTO) {
        //调用微信接口服务，获得当前微信用户的openid
        String openId=getOpenId(userLoginDTO.getCode());
        //判断openid是否为空，如果为空表示登录失败，跑出业务异常
        User user=userMapper.getByOpenId(openId);
        //判断当前用户是否为新用户
        if(user==null){
            user = User.builder().openid(openId).createTime(LocalDateTime.now()).build();
            userMapper.insert(user);
        }
        //如果是新用户，自动完成注册
        //返回这个用户对象
        return user;
    }

    private String getOpenId(String code){
        Map<String,String> map=new HashMap<>();
        map.put("appid", weChatProperties.getAppid());
        map.put("secret", weChatProperties.getSecret());
        map.put("js_code", code);
        map.put("grant_type","authorization_code");
        String json= HttpClientUtil.doGet(WX_LOGIN,map);

        JSONObject jsonObject= JSONObject.parseObject(json);
        return jsonObject.getString("openid");
    }
}
