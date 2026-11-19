# 房型图片配置

“办理酒店业务”通过现有酒店列表和按酒店 ID 查询房型的接口展示数据。
输入名称、地址、电话或酒店 ID 搜索；单个结果自动展示房型，多个结果点击选择。

目前提供本地 SVG 房型示意图，页面标记为“非实拍”。没有上传真实酒店照片。

## 替换真实房型图片

1. 将照片放入 `gateway/src/main/resources/static/assets/rooms/`，例如 `room-4.jpg`。
2. 编辑 `gateway/src/main/resources/static/assets/room-images.json`，以**房型 ID**为键：

```json
{
  "4": "/assets/rooms/room-4.jpg",
  "5": "/assets/rooms/room-5.jpg"
}
```

3. 重启项目并刷新页面。图片路径必须以 `/assets/` 开头。

未配置或图片加载失败时自动显示对应房型示意图。
本模块目前展示酒店和房型资料；办理预订、支付、入住仍需后续业务接口支持。
