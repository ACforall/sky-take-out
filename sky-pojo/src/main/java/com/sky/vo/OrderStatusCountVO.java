package com.sky.vo;

import lombok.Data;

import java.io.Serializable;
//自己加的vo对象，用来承接按状态统计订单数据的mapper层返回值
@Data
public class OrderStatusCountVO implements Serializable {
    private Integer status;   // 对应 status
    private Integer cnt;
}
