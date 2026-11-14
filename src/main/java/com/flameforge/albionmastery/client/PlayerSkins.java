package com.flameforge.albionmastery.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 按 UUID + 名字拿玩家皮肤贴图，排行榜的头像和排名横幅共用。
 *
 * <p>在线的人直接用 TAB 列表里已经加载好的那份，不用再去拉。离线的人拼一个
 * GameProfile，走原版玩家头颅用的那条路（{@link SkullBlockEntity#fetchGameProfile(String)}）异步补全
 * 皮肤信息：正版服拉到的是本人皮肤；用 authlib-injector 这类皮肤站的客户端，会话服务已经被
 * 换成皮肤站的，同样能拉到。拉不到（离线服、名字不存在）就退回按 UUID 分到的原版默认皮肤，
 * 不会卡住也不会报错。拉皮肤要访问网络，结果按 UUID 缓存，每个人只拉一次。
 */
public final class PlayerSkins {

    /** 离线玩家的皮肤，按 UUID 缓存，本次游戏进程内有效。皮肤贴图本身是原版 SkinManager 缓存的，这里只记地址。 */
    private static final Map<UUID, ResourceLocation> CACHE = new HashMap<>();

    /** 正在拉皮肤的玩家，别重复发请求。 */
    private static final Set<UUID> PENDING = new HashSet<>();

    private PlayerSkins() {
    }

    /**
     * 拿一名玩家的皮肤贴图地址。
     *
     * @param uuid 玩家 UUID
     * @param name 玩家名，离线玩家靠它去查
     * @return 皮肤贴图地址，永不为 null；还没拉到时先给默认皮肤
     */
    public static ResourceLocation skinOf(UUID uuid, String name) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(uuid);
            if (info != null) {
                return info.getSkin().texture();
            }
        }
        ResourceLocation cached = CACHE.get(uuid);
        if (cached != null) {
            return cached;
        }
        if (!name.isEmpty() && PENDING.add(uuid)) {
            // 按名字补全 profile；查不到时退回只有 UUID 和名字的 profile，它没有材质，拿到的就是默认皮肤
            SkullBlockEntity.fetchGameProfile(name)
                    .thenCompose(found -> minecraft.getSkinManager().getOrLoad(found.orElse(new GameProfile(uuid, name))))
                    .handle((skin, error) -> {
                        // 完成时不一定在主线程，回主线程再动缓存，这两个集合不是线程安全的
                        minecraft.execute(() -> {
                            CACHE.put(uuid, error == null && skin != null
                                    ? skin.texture() : DefaultPlayerSkin.get(uuid).texture());
                            PENDING.remove(uuid);
                        });
                        return null;
                    });
        }
        return DefaultPlayerSkin.get(uuid).texture();
    }
}
