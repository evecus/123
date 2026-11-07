# 依赖均自带 consumer rules（Compose / DataStore / Navigation / coroutines），
# 项目本身无反射加载，无需额外 keep。
# 若后续引入 Gson / Room / 序列化库，再按库要求补 keep。

# Compose 图标（material-icons-extended）：未用图标由 R8 自动剔除，勿加 -keep

# 忽略元数据相关告警（R8 全模式下常见）
-dontwarn java.lang.invoke.StringConcatFactory
