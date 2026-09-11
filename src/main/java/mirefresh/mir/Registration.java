package mirefresh.mir;

import mirefresh.mir.mi.MiElectricCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Central registry of MI-Refresh content. Currently just the MI bridge's hidden companion block
 * entity type — never placed in the world, it exists purely to host PowerGrid's electrical
 * behaviour for an MI machine (see {@link MiElectricCompanion}).
 */
public final class Registration {

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MiElectricCompanion>> MI_COMPANION_BE =
            Mir.BLOCK_ENTITIES.register("mi_companion",
                    () -> BlockEntityType.Builder.<MiElectricCompanion>of(Registration::makeMiCompanion).build(null));

    private static MiElectricCompanion makeMiCompanion(BlockPos pos, BlockState state) {
        return new MiElectricCompanion(MI_COMPANION_BE.get(), pos, state);
    }

    private Registration() {}

    /** Force class-load so the static {@code register(...)} call above runs. */
    public static void init() {}
}
