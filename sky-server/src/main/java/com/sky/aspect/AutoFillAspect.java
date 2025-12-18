package com.sky.aspect;

import com.sky.annotation.AutoFill;
import com.sky.constant.AutoFillConstant;
import com.sky.context.BaseContext;
import com.sky.enumeration.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.poifs.crypt.dsig.ExpiredCertificateSecurityException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 自定义切面，实现公共字段自动填充逻辑
 */
@Aspect
@Component
@Slf4j
public class AutoFillAspect {
    /**
     * 切入点
     * 切点表达式：拦截 com.sky.mapper 包下所有类中的所有方法，并且该方法必须标注了 @AutoFill 注解。
     */
    @Pointcut("execution(* com.sky.mapper.*.*(..)) && @annotation(com.sky.annotation.AutoFill)")
    public void autoFillPointcut(){

    }

    /**
     * 前置通知，在通知中进行公共字段的赋值
     * @param joinPoint
     */
    @Before("autoFillPointcut()")
    public void autoFill(JoinPoint joinPoint){
        log.info("开始填充公共字段...");
        //获取当前被拦截方法数据库操作类型
        MethodSignature signature= (MethodSignature) joinPoint.getSignature();//方法签名对象
        AutoFill autoFill=signature.getMethod().getAnnotation(AutoFill.class);//获得方法上的注解对象
        OperationType operationType=autoFill.value();//获得数据库操作类型
        //获取当前被拦截方法的参数--实体对象,约定：在mapper方法中如果有多个参数把实体对象放第一个
        Object[] args=joinPoint.getArgs();
        if(args==null||args.length==0){
            return;
        }
        Object entity=args[0];
        //准备赋值数据
        LocalDateTime now=LocalDateTime.now();
        Long currentId= BaseContext.getCurrentId();
        //根据当前不同的操作类型，为对应的属性通过反射赋值
        if(operationType==OperationType.UPDATE){
            try {
                //获取实体对象上的方法
                Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
                Method setUpdateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);
                //通过反射赋值
                setUpdateTime.invoke(entity,now);
                setUpdateUser.invoke(entity,currentId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        }
        else if(operationType==OperationType.INSERT){
            try {
                Method setCreateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_TIME, LocalDateTime.class);
                Method setCreateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_USER, Long.class);
                Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
                Method setUpdateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);
                setCreateTime.invoke(entity,now);
                setCreateUser.invoke(entity,currentId);
                setUpdateTime.invoke(entity,now);
                setUpdateUser.invoke(entity,currentId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
