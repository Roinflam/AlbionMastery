// 路径：src/main/java/com/flameforge/albionmastery/mastery/NpcExperienceReader.java
package com.flameforge.albionmastery.mastery;

import com.flameforge.albionmastery.AlbionMastery;
import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.OptionalDouble;

/**
 * 读自定义 NPC（CustomNPCs）在编辑界面里设的掉落经验，不引用它的任何类。
 *
 * <p>NPC 编辑页「掉落」那一栏的经验是 {@code EntityNPCInterface.inventory.minExp / maxExp}，
 * 对外有 {@code getExpMin()} / {@code getExpMax()}。这里用反射拿：按名字找 {@code inventory} 字段，
 * 再在它上面找那两个方法。第一次找到之后把句柄缓存起来，之后每次就是两次方法句柄调用，
 * 和直接调没什么区别。
 *
 * <p>为什么用反射而不是直接引用：CustomNPCs 不是本模组的依赖，编译期没有它的 jar，
 * 运行期也可能没装。反射找不到就返回空，调用方回退到配置里的固定值。
 *
 * <p>取的是 min 和 max 的平均值而不是它随机出来的那个数，同样的 NPC 每次给的专精经验一样多。
 */
public final class NpcExperienceReader {

    /** 缓存的句柄对应的实体类，换了类就重新找。 */
    private static Class<?> cachedClass;

    private static Field inventoryField;

    private static MethodHandle expMinHandle;

    private static MethodHandle expMaxHandle;

    /** 这个类上已经找过并且失败了，不再重复尝试、不再重复刷日志。 */
    private static Class<?> failedClass;

    private NpcExperienceReader() {
    }

    /**
     * 读这只 NPC 设定的掉落经验。
     *
     * @param entity NPC 实体
     * @return min / max 的平均值；读不到返回空。使用 double 保留 0.5 这类半点经验，避免整数平均直接截成 0
     */
    public static OptionalDouble read(LivingEntity entity) {
        Class<?> type = entity.getClass();
        if (type == failedClass) {
            return OptionalDouble.empty();
        }
        try {
            if (type != cachedClass) {
                resolve(type);
            }
            Object inventory = inventoryField.get(entity);
            if (inventory == null) {
                return OptionalDouble.empty();
            }
            int min = (int) expMinHandle.invoke(inventory);
            int max = (int) expMaxHandle.invoke(inventory);
            return OptionalDouble.of(Math.max(0.0D, (min + max) / 2.0D));
        } catch (Throwable throwable) {
            failedClass = type;
            cachedClass = null;
            AlbionMastery.LOGGER.warn("读取自定义 NPC 掉落经验失败，这类 NPC 改用配置里的固定值: {}",
                    type.getName(), throwable);
            return OptionalDouble.empty();
        }
    }

    /**
     * 在实体类及其父类里找 {@code inventory} 字段，再找它上面的两个取值方法。
     *
     * @param type 实体类
     * @throws ReflectiveOperationException 找不到时抛出
     */
    private static void resolve(Class<?> type) throws ReflectiveOperationException {
        Field field = null;
        for (Class<?> current = type; current != null && field == null; current = current.getSuperclass()) {
            try {
                field = current.getDeclaredField("inventory");
            } catch (NoSuchFieldException ignored) {
                // 往父类继续找
            }
        }
        if (field == null) {
            throw new NoSuchFieldException("inventory");
        }
        field.setAccessible(true);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> inventoryType = field.getType();
        expMinHandle = lookup.unreflect(inventoryType.getMethod("getExpMin"));
        expMaxHandle = lookup.unreflect(inventoryType.getMethod("getExpMax"));
        inventoryField = field;
        cachedClass = type;
    }
}


