# 企业商品库货品创建与修改

- `POST /company-goods/create`：独立 DTO `CompanyGoodsCreateDTO`，支持一次创建主货品和完整子货品，返回主货品本地主键。
- `POST /company-goods/modify`：独立 DTO `CompanyGoodsModifyDTO`，在创建字段基础上增加 `id`、`version`，仅覆盖非空字段；SKU 编码不可变。id 为空返回业务错误；version 为空使用数据库当前版本。
- 原 `/company-goods/update` 保持不变。

两个接口只写本地，不调用领星、黑湖，不产生 Kafka 消息。创建使用企业商品库来源、商品库接入方式（1）、已创状态（1）、版本 0；修改保留来源、创建方式及系统状态，版本加一。

创建请求的 `group_list` 是完整子货品数组，子项为 `CompanyGoodsChildCreateDTO`，包含所有货品公共字段及 `quantity`（比例数量）。一主三子生成四条 company_goods 数据，任意一条保存失败整体事务回滚。仅创建新记录，已存在的 SKU 或名称报错；请求内主、子 SKU 和名称也不可重复。子项不支持继续嵌套。

```json
{
  "sku": "PARENT-001",
  "goods_name": "主货品",
  "group_list": [
    {"sku": "CHILD-001", "goods_name": "子货品一", "quantity": 2, "supplier_name": "供应商甲"},
    {"sku": "CHILD-002", "goods_name": "子货品二", "quantity": 3},
    {"sku": "CHILD-003", "goods_name": "子货品三", "quantity": 1}
  ]
}
```

主货品持久化的 `group_list` 只记录 `sku`、`quantity`、`goods_name`，完整子货品字段分别写入子货品记录。比例数量只写入主货品关联，允许为空，非空需为正数且最多两位小数。主货品无子项时为普通产品，否则为组合产品；子货品为普通产品。所有公共字段继续允许为空，不自动补造 SKU 或名称。

修改接口的 `group_list` 也是完整子货品数组，子项为 `CompanyGoodsChildModifyDTO`，含公共货品字段、`id`、`version`、`quantity`：有 id 则修改已有普通货品，无 id 则新增；带 id 但不存在时报错，不按 SKU 自动匹配更新。子项 id 不可重复或等于主货品 id，已有 SKU 编码不可修改。子货品版本为空使用数据库当前版本，非空时校验；空业务字段保留原值。已有关系的 quantity 为空保留原比例数量，新关系未传则为空。主货品关联名称使用保存后的子货品名称。

`group_list` 为 null 保留原关联，[] 清空关联；移除子项只解除关系，不删除独立子货品。主、子项整体事务保存，任意校验、插入、更新或版本条件失败整体回滚。只支持一层子货品，不能将组合产品作为子项；已被其他主货品引用的货品不能增加子项。创建与修改共享新的公共字段 DTO `CompanyGoodsSaveDTO`，均不依赖原页面编辑 DTO。

```json
{
  "id": 100,
  "goods_name": "主货品新名称",
  "group_list": [
    {"id": 101, "goods_name": "已有子货品新名称", "quantity": 2, "supplier_name": "新供应商"},
    {"sku": "NEW-CHILD", "goods_name": "新增子货品", "quantity": 3}
  ]
}
```

字段使用 snake_case；所有顶层字段允许为空；非空业务字段沿用现有表单的长度、金额精度及范围规则，但新 DTO 不继承或引用原编辑 DTO。SKU 和名称校验重复。产品分类校验本地枚举，检验类型校验有效名称且不可重复。

图片使用 `picture_list`，元素为 `object_key`、`pic_url`、`is_primary`，接收已上传的图片元数据，不进行 S3 上传和文件删除。创建时未传或空数组保存为空列表；修改时未传保留原值，空数组清空。此接口的图片处理与原页面 `/update` 的 `image_url` 不同。

执行入口：`CompanyGoodsController` → `CompanyGoodsLocalSaveService` → `CompanyGoodsMapper.insertLocal/modifyLocal`。整体事务保存；修改通过版本条件避免覆盖并发修改。
