package mirefresh.mir.mi;

import aztech.modern_industrialization.api.energy.CableTier;
import aztech.modern_industrialization.machines.blockentities.TransformerMachineBlockEntity;
import mirefresh.mir.Mir;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MI's cable-tier transformer blocks ({@code lv_mv_transformer}, {@code mv_lv_transformer}, ...)
 * step voltage between MI's own cable tiers &mdash; a mechanic that now belongs to PowerGrid's own
 * transformer coupling instead. This scrubs them from the world entirely: every time a chunk
 * finishes loading, any transformer block found in it is deleted outright (set to air, no item
 * drop, no recovery &mdash; the same as a creative {@code /setblock}, not a survival break).
 *
 * <p>Crafting/placing new ones isn't blocked separately: whatever a player places will simply
 * vanish the next time its chunk reloads, same as any that already exist in the world.
 */
@EventBusSubscriber(modid = Mir.MODID)
public final class MiTransformerRemoval {

    private MiTransformerRemoval() {}

    // Lazy and cached: computed the first time a chunk actually loads, long after MI's own
    // registration (and any KubeJS custom-tier event) has run, so CableTier.allTiers() is complete.
    private static Set<ResourceLocation> transformerIds;

    private static Set<ResourceLocation> transformerIds() {
        if (transformerIds == null) {
            Set<ResourceLocation> found = new HashSet<>();
            // Mirrors MI's own SingleBlockSpecialMachines#registerTransformers: adjacent pairs in
            // the full tier list, plus adjacent-among-builtin pairs (covers a modpack inserting a
            // custom tier between two builtin ones via KubeJS's cable-tier event).
            List<CableTier> tiers = CableTier.allTiers();
            for (int i = 0; i < tiers.size() - 1; i++) {
                addPair(found, tiers.get(i), tiers.get(i + 1));
            }
            List<CableTier> builtin = tiers.stream().filter(t -> t.builtin).toList();
            for (int i = 0; i < builtin.size() - 1; i++) {
                addPair(found, builtin.get(i), builtin.get(i + 1));
            }
            transformerIds = Set.copyOf(found);
        }
        return transformerIds;
    }

    private static void addPair(Set<ResourceLocation> out, CableTier low, CableTier high) {
        out.add(id(TransformerMachineBlockEntity.getTransformerName(low, high)));
        out.add(id(TransformerMachineBlockEntity.getTransformerName(high, low)));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("modern_industrialization", path);
    }

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!ModList.get().isLoaded("modern_industrialization")) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        Set<ResourceLocation> transformers = transformerIds();
        List<BlockPos> toRemove = null;
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlockState().getBlock());
            if (transformers.contains(blockId)) {
                if (toRemove == null) toRemove = new ArrayList<>();
                toRemove.add(entry.getKey().immutable());
            }
        }
        if (toRemove == null) return;

        // Collect first, then mutate: setBlock() removes the old block entity from the very map
        // we're iterating above, so doing it in the same pass would be a ConcurrentModificationException.
        for (BlockPos pos : toRemove) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Mir.LOGGER.info("[mir] Removed {} MI transformer block(s) from chunk {}", toRemove.size(), chunk.getPos());
    }
}
