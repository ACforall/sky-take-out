package com.sky.service.impl;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.vo.OrderReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    /**
     * 营业额数据统计
     * @param begin
     * @param end
     * @return
     */
    @Override
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        //当前集合用于存放begin到end范围内每天的日期
        List<LocalDate> dateList=new ArrayList<>();
        while (!begin.equals(end)){
            dateList.add(begin);
            begin=begin.plusDays(1);
        }
        dateList.add(end);

        //计算每天的营业额
        List<Double> turnoverList=new ArrayList<>();
        for (LocalDate date : dateList) {
            //查询date日期对应的营业额数据，营业额是指，状态为“已完成”的订单金额合计

            //select sum(amount) from orders where order_time>? and order_time<? and status=5
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);
            Map map=new HashMap();
            map.put("begin",beginTime);
            map.put("end",endTime);
            map.put("status", Orders.COMPLETED);
            Double turnover=orderMapper.sumByMap(map);
            //如果当天没有数据，会返回空，所以需要处理一下
            turnover= turnover==null?0.0:turnover;
            turnoverList.add(turnover);
        }

        return TurnoverReportVO
                .builder()
                .dateList(StringUtils.join(dateList,","))
                .turnoverList(StringUtils.join(turnoverList,","))
                .build();
    }

    /**
     * 统计指定时间内用户数据
     * @param begin
     * @param end
     * @return
     */
    @Override
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        //当前集合用于存放begin到end范围内每天的日期
        List<LocalDate> dateList=new ArrayList<>();
        while (!begin.equals(end)){
            dateList.add(begin);
            begin=begin.plusDays(1);
        }
        dateList.add(end);

        //select count(id) from user where create_time<? and create_time>?
        List<Integer> newUserList=new ArrayList<>();
        //select count(id) from user where create_time<?
        List<Integer> totalUserList=new ArrayList<>();

        for (LocalDate date : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            //因为总用户数量只需要end，所以先查end
            Map map=new HashMap();
            map.put("end", endTime);
            Integer totalUser = userMapper.countByMap(map);

            //加入begin求新增用户
            map.put("begin",beginTime);
            Integer newUser = userMapper.countByMap(map);

            newUserList.add(newUser);
            totalUserList.add(totalUser);
        }

        return UserReportVO
                .builder()
                .dateList(StringUtils.join(dateList,","))
                .newUserList(StringUtils.join(newUserList,","))
                .totalUserList(StringUtils.join(totalUserList,","))
                .build();
    }

    /**
     * 订单统计
     * @param begin
     * @param end
     * @return
     */
    @Override
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        //当前集合用于存放begin到end范围内每天的日期
        List<LocalDate> dateList=new ArrayList<>();
        while (!begin.equals(end)){
            dateList.add(begin);
            begin=begin.plusDays(1);
        }
        dateList.add(end);

        List<Integer> orderCountList=new ArrayList<>();
        List<Integer> validOrderCountList=new ArrayList<>();

        for (LocalDate date : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            //查询每天的订单总数
            Integer orderCount=getOrderCount(beginTime,endTime,null);
            //查询每天的有效订单数
            Integer validOrderCount=getOrderCount(beginTime,endTime,Orders.COMPLETED);

            orderCountList.add(orderCount);
            validOrderCountList.add(validOrderCount);
        }

        //计算这个时间段之内订单总数
        Integer totalOrderCount = orderCountList.stream().reduce(Integer::sum).get();

        //计算这个时间段之内有效订单总数
        Integer validOrderCount = validOrderCountList.stream().reduce(Integer::sum).get();

        //计算订单完成率
        Double orderCompletionRate=0.0;
        if(totalOrderCount!=0){
            orderCompletionRate=validOrderCount.doubleValue()/totalOrderCount;
        }

        return OrderReportVO
                .builder()
                .dateList(StringUtils.join(dateList,","))
                .orderCountList(StringUtils.join(orderCountList,","))
                .validOrderCountList(StringUtils.join(validOrderCountList,","))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();
    }

    /**
     * 根据条件统计订单数据
     * @param begin
     * @param end
     * @param status
     * @return
     */
    private Integer getOrderCount(LocalDateTime begin, LocalDateTime end, Integer status){
        Map map=new HashMap<>();
        map.put("begin",begin);
        map.put("end",end);
        map.put("status",status);
        return orderMapper.countByMap(map);
    }
}
