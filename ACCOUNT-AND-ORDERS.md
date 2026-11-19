# 我的订单与个人中心

重启 Spring Boot，浏览器强制刷新后，可从左侧导航进入两个新模块。

## 我的订单

- `GET /hotel/orders/all` 返回 JWT 对应用户的全部订单。
- 支持按状态筛选、按酒店/房型/订单编号搜索、查看订单详情。
- 待付款订单可继续付款，或调用 `POST /hotel/orders/{id}/cancel` 取消。
- 取消与付款使用带原状态条件的数据库更新；已付款订单不能取消，已取消订单不能付款。
- 已到退房时间的订单在历史列表显示「已结束」，仍保留数据库记录。
- 已从退款模块隐藏的记录，仍显示在我的订单中。
- 启动时自动为旧订单表增加 `cancelled_time` 字段，需要数据库 ALTER 权限。

## 个人中心

- `GET /hotel/profile` 返回当前用户安全资料，不包含密码哈希。
- `PUT /hotel/profile` 接收 `nickname` 和 `phone`，仅更新当前用户这些字段。
- `POST /hotel/profile/password` 接收 `oldPassword` 和 `newPassword`，检查原密码并使用 BCrypt 保存新密码。
- 不接受前端指定用户 ID、角色、状态或用户名来修改账号。
- 昵称最多50字符；电话可留空，最多20字符。密码至少8字符，UTF-8最多72字节。
- 当前 JWT 机制中，修改密码不会撤销已签发令牌；已有令牌在有效期内仍可用。令牌撤销属于后续会话管理功能。

## 验证

`mvn -pl account-service,order-service -am -Dtest=StayOrderServiceTest,ProfileServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

检查历史订单、取消与付款状态限制、用户隔离、资料字段校验和密码哈希。
