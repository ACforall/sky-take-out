package com.sky.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.AddressOutOfBoundaryException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.properties.BaiduMapProperties;
import com.sky.properties.ShopProperties;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.*;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private BaiduMapProperties baiduMapProperties;
    @Autowired
    private ShopProperties shopProperties;
    @Autowired
    private WebSocketServer webSocketServer;

    //地理编码接口
    private static final String geocodeURL = "https://api.map.baidu.com/geocoding/v3/";
    //轻量路径规划接口
    private static final String directionURL = "https://api.map.baidu.com/directionlite/v1/riding";


    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        //1.处理各种业务异常（地址簿为空，购物车数据为空）
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if(addressBook==null){
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        //地址超出5km范围
        String shopAddress=shopProperties.getAddress();
        Integer distance=shopProperties.getDistance();
        if(isOutOfDistance(addressBook.getDetail(),shopAddress,distance)){
            throw new AddressOutOfBoundaryException(MessageConstant.ADDRESS_TOO_FAR);
        };
        //查询当前用户的购物车数据
        ShoppingCart shoppingCart=new ShoppingCart();
        Long userId= BaseContext.getCurrentId();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if(shoppingCartList==null|| shoppingCartList.isEmpty()){
            throw new ShoppingCartBusinessException((MessageConstant.SHOPPING_CART_IS_NULL));
        }
        //2.向订单表插入1条数据
        Orders orders=new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setAddress(addressBook.getDetail());
        orders.setUserId(userId);

        orderMapper.insert(orders);

        //3.向订单明细表插入n条数据
        List<OrderDetail> orderDetailList=new ArrayList<>();
        for(ShoppingCart cart:shoppingCartList){
            OrderDetail orderDetail=new OrderDetail();
            BeanUtils.copyProperties(cart,orderDetail);
            orderDetail.setOrderId(orders.getId());//设置当前订单明细关联的订单id
            orderDetailList.add(orderDetail);

        }
        orderDetailMapper.insertBatch(orderDetailList);
        //4.清空用户的购物车数据
        shoppingCartMapper.cleanAll(userId);
        //5.封装vo返回结果
        OrderSubmitVO orderSubmitVO=OrderSubmitVO.builder()
                .id(orders.getId())
                .orderTime(orders.getOrderTime())
                .orderAmount(orders.getAmount())
                .orderNumber(orders.getNumber())
                .build();
        return orderSubmitVO;
    }

    private boolean isOutOfDistance(String detailAddress, String shopAddress, Integer distance) {
        //调用地理编码接口，获取origin和destination的经纬度坐标
        JSONObject destinationLocation=getLocation(detailAddress);
        JSONObject originLocation=getLocation(shopAddress);

        Double destLat = destinationLocation.getDouble("lat");
        Double destLng = destinationLocation.getDouble("lng");

        Double originLat = originLocation.getDouble("lat");
        Double originLng = originLocation.getDouble("lng");
        //轻量路径规划接口，得到距离

        Map<String,String> paramMap=new HashMap<>();
        paramMap.put("ak",baiduMapProperties.getApiKey());
        paramMap.put("output","json");
        paramMap.put("origin", originLat + "," + originLng);
        paramMap.put("destination", destLat + "," + destLng);

        String result=HttpClientUtil.doGet(directionURL, paramMap);

        JSONObject jsonObject = JSONObject.parseObject(result);

        //解析返回值

        if (jsonObject.getInteger("status") == 0) {
            JSONObject resultObj = jsonObject.getJSONObject("result");
            JSONArray routes = resultObj.getJSONArray("routes");

            if (routes != null && !routes.isEmpty()) {
                JSONObject route = routes.getJSONObject(0);

                Integer realDistance = route.getInteger("distance"); //m

                //判断距离是否符合预期
                return realDistance > distance*1000;
            }
        }

        return false;
    }

    private JSONObject getLocation(String address){
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("address", address);
        paramMap.put("output", "json");
        paramMap.put("ak", baiduMapProperties.getApiKey());

        String result = HttpClientUtil.doGet(geocodeURL, paramMap);

        JSONObject jsonObject= JSONObject.parseObject(result);

        Integer status = jsonObject.getInteger("status");

        if (status == 0) {
            return  jsonObject
                    .getJSONObject("result")
                    .getJSONObject("location");
        }
        else return null;
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        //调用微信支付接口，生成预支付交易单
//        JSONObject jsonObject = weChatPayUtil.pay(
//                ordersPaymentDTO.getOrderNumber(), //商户订单号
//                new BigDecimal(0.01), //支付金额，单位 元
//                "苍穹外卖订单", //商品描述
//                user.getOpenid() //微信用户的openid
//        );
        JSONObject jsonObject=new JSONObject();

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);

        //通过websocket向客户端浏览器推送消息type orderid content
        Map map=new HashMap();//可以不指定放的类型诶
        map.put("type",1);//1表示来单提醒2表示客户催单
        map.put("orderId",ordersDB.getId());
        map.put("content","订单号："+outTradeNo);
        String json= JSONArray.toJSONString(map);
        webSocketServer.sendToAllClient(json);
    }

    /**
     * 历史订单查询
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(),ordersPageQueryDTO.getPageSize());
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        Page<OrderVO> page=orderMapper.pageQuery(ordersPageQueryDTO);
        for(OrderVO vo:page){
            vo.setOrderDetailList(orderDetailMapper.getByOrderId(vo.getId()));
        }
        return new PageResult(page.getTotal(),page.getResult());
    }

    /**
     * 查询订单详情
     * @param orderId
     * @return
     */
    @Override
    public OrderVO getOrderDetailByOrderId(Long orderId) {
        Orders order = orderMapper.getByOrderId(orderId);
        OrderVO orderVO=new OrderVO();
        BeanUtils.copyProperties(order,orderVO);
        orderVO.setOrderDetailList(orderDetailMapper.getByOrderId(orderId));
        return orderVO;
    }

    /**
     * 取消订单
     * @param id
     */
    @Override
    public void cancel(Long id) {
        //根据id查询订单
        Orders ordersDB=orderMapper.getByOrderId(id);
        //校验订单是否存在
        if(ordersDB==null){
            throw  new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        //订单状态校验：用户无法取消商家已经接单的订单（以及后面的状态）1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        if(ordersDB.getStatus()>2){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        //订单处于待接单状态下取消，需要进行退款
        Orders orders=Orders.builder()
                .status(Orders.CANCELLED)
                .id(id)
                .cancelReason("用户取消")
                .cancelTime(LocalDateTime.now()).build();
        if(ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            //调用微信退款接口
            orders.setPayStatus(Orders.REFUND);
        }

        orderMapper.update(orders);
    }

    /**
     * 再来一单
     * @param id
     */
    @Override
    public void repetition(Long id) {
        //先把orderId对应的数据全部查出来
        List<OrderDetail> oldDetails=orderDetailMapper.getByOrderId(id);
        //放到shopping cart里面？
        for(OrderDetail detail:oldDetails){
            ShoppingCart shoppingCart=new ShoppingCart();
            BeanUtils.copyProperties(detail,shoppingCart);
            shoppingCart.setUserId(BaseContext.getCurrentId());
            shoppingCart.setCreateTime(LocalDateTime.now());
            shoppingCartMapper.insert(shoppingCart);
        }
    }

    /**
     * 管理端搜索订单（不设置userid,返回值不需要detaillist，需要orderDishes字符串)
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(),ordersPageQueryDTO.getPageSize());
        Page<OrderVO> page=orderMapper.pageQuery(ordersPageQueryDTO);
        for(OrderVO vo:page){
            List<OrderDetail> details = orderDetailMapper.getByOrderId(vo.getId());
            vo.setOrderDishes(orderDishNames(details));
        }
        return new PageResult(page.getTotal(),page.getResult());
    }

    /**
     * 统计不同状态下的订单数量
     * @return
     */
    @Override
    public OrderStatisticsVO getStatistics() {
        //先统计每个状态的数量
        List<OrderStatusCountVO> orderStatusCountVO = orderMapper.countGroupByStatus();

        //2，3，4分别对应待接单，待派送，派送中
        OrderStatisticsVO orderStatisticsVO= new OrderStatisticsVO();
        for(OrderStatusCountVO vo:orderStatusCountVO){
            if(vo.getStatus()==Orders.TO_BE_CONFIRMED){
                orderStatisticsVO.setToBeConfirmed(vo.getCnt());
            }
            if(vo.getStatus()==Orders.CONFIRMED){
                orderStatisticsVO.setConfirmed(vo.getCnt());
            }
            if(vo.getStatus()==Orders.DELIVERY_IN_PROGRESS){
                orderStatisticsVO.setDeliveryInProgress(vo.getCnt());
            }
        }
        return orderStatisticsVO;
    }

    /**
     * 接单
     * @param ordersConfirmDTO
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders=Orders.builder().id(ordersConfirmDTO.getId()).status(ordersConfirmDTO.getStatus()).build();
        orderMapper.update(orders);
    }

    /**
     * 拒单
     * @param ordersRejectionDTO
     */
    @Override
    public void reject(OrdersRejectionDTO ordersRejectionDTO) {
        //根据id查询订单
        Orders ordersDB=orderMapper.getByOrderId(ordersRejectionDTO.getId());
        //订单只有存在且状态为2（待接单）才可以拒单
        if(ordersDB==null||!ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        //更新对象
        Orders orders=Orders.builder()
                .id(ordersRejectionDTO.getId())
                .rejectionReason(ordersRejectionDTO.getRejectionReason())
                .cancelTime(LocalDateTime.now())
                .status(Orders.CANCELLED).build();
        //支付状态为paid时需要退款
        Integer payStatus=ordersDB.getPayStatus();
        if(payStatus==Orders.PAID){
            //微信退款调用
            orders.setPayStatus(Orders.REFUND);
        }

        orderMapper.update(orders);
    }

    /**
     * 取消订单
     * @param ordersCancelDTO
     */
    @Override
    public void adminCancel(OrdersCancelDTO ordersCancelDTO) {
        Orders orders=Orders.builder()
                .status(Orders.CANCELLED)
                .id(ordersCancelDTO.getId())
                .payStatus(Orders.REFUND)
                .cancelReason(ordersCancelDTO.getCancelReason())
                .cancelTime(LocalDateTime.now()).build();
        orderMapper.update(orders);
    }

    /**
     * 配送订单
     * @param id
     */
    @Override
    public void deliver(Long id) {
        //根据id查询订单
        Orders ordersDB=orderMapper.getByOrderId(id);
        //校验订单是否存在，并且状态为3
        if(ordersDB==null||!ordersDB.getStatus().equals(Orders.CONFIRMED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders=Orders.builder()
                .id(id)
                .status(Orders.DELIVERY_IN_PROGRESS)
                .build();
        orderMapper.update(orders);
    }

    /**
     * 完成订单
     * @param id
     */
    @Override
    public void complete(Long id) {
        //根据id查询订单
        Orders ordersDB=orderMapper.getByOrderId(id);
        //校验订单是否存在，并且状态为4
        if(ordersDB==null||!ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders=Orders.builder()
                .id(id)
                .status(Orders.COMPLETED)
                .deliveryTime(LocalDateTime.now())
                .build();
        orderMapper.update(orders);
    }

    private String orderDishNames(List<OrderDetail> orderDetails){
        StringBuffer stringBuffer=new StringBuffer();
        for(OrderDetail orderDetail:orderDetails){
            stringBuffer.append(orderDetail.getName()+"*"+orderDetail.getNumber());
            stringBuffer.append(";");
        }
        return stringBuffer.toString();
    }


}
