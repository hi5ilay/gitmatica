# LVC git based structure format

Note: this document describes the older/current `index.nbt` implementation path. The new MVP storage direction is the semantic content-addressed chunk format in `docs/tech/lvc-semantic-storage.md`. When these conflict for new architecture work, follow `docs/prd-corrections.md` and `docs/tech/lvc-semantic-storage.md`.

LVC 是把 Minecraft 结构版本接入 Git 的重大尝试。

所有新的类必须写在me.zly2006.lvc包下。

一个 LVC project 是一个 Git repo，它可以在 GitHub 上被同步。作为 MVP，首先要实现 commit。

## 目录结构

- index.json （LVC 元数据和 sub-region 定义）
- index.nbt （使用原版 vanilla structure NBT 保存的主内容）
- local.json （本地 workspace 状态，必须写入 .gitignore）

项目使用jgit操作git。

## structure 存储

LVC 主内容使用原版 `StructureTemplate` 的 `.nbt` 格式，而不是旧的 Schematica `.schematic` 格式。

导出时，LVC 负责把多个 tracked sub-region 投影到一个临时 schematic world：

- tracked sub-region 内的方块从真实世界复制。
- tracked sub-region 外、但位于 enclosing cuboid 内的位置写成 `minecraft:structure_void`。
- 然后调用 `StructureTemplate#fillFromWorld` 和 `StructureTemplate#save` 写出 `index.nbt`。

恢复时，LVC 不手写 vanilla structure 的 palette、blocks 或 entity NBT 解析。`index.nbt` 通过 Litematica 已有的 vanilla structure loader 读取，并通过已有的 placement/paste 逻辑写回游戏内。`structure_void` 用于保证独立 sub-region 之间的空隙不会被 checkout/pull 修改。

## git commit 元数据

对于所有使用本模组创建的commit，必须包括自定义git元数据。
使用 jgit 的 ObjectInserter 可以添加元数据。

元数据如下：

- author、committer：name字段使用当前玩家的玩家名字，email字段使用{uuid}@minecraft
- 特殊元数据：lvc-version，取1
- 特殊元数据：x-created-by，取lvc
