package mirefresh.mir.mi;

import aztech.modern_industrialization.pipes.MIPipes;
import aztech.modern_industrialization.pipes.api.PipeNetworkNode;
import aztech.modern_industrialization.pipes.api.PipeNetworkType;
import aztech.modern_industrialization.pipes.impl.PipeBlockEntity;
import mirefresh.mir.Mir;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * MI's cable network &mdash; every material (tin, copper, silver, ...) and voltage tier is just the
 * electricity {@link PipeNetworkType} of MI's shared pipe system (there's only one real block,
 * {@code modern_industrialization:pipe}; item pipes, fluid pipes and cables are different network
 * types layered onto the same {@link PipeBlockEntity}, distinguished at runtime, not by separate
 * block ids) &mdash; is now entirely replaced by PowerGrid wires attached straight to each machine's
 * connector. Every time a chunk finishes loading, this strips any electricity-network connection
 * from every pipe block found in it, via MI's own {@link PipeBlockEntity#removePipeAndDropContainedItems}
 * (the same call MI's own pipe-cutting tool makes) &mdash; leaving any item/fluid pipe sharing that
 * same block position untouched.
 *
 * <p>Crafting/placing new cable isn't blocked separately: whatever a player places will simply be
 * stripped back out the next time its chunk reloads, same as any that already exist in the world.
 */
@EventBusSubscriber(modid = Mir.MODID)
public final class MiCableRemoval {

    private MiCableRemoval() {}

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!ModList.get().isLoaded("modern_industrialization")) return;
        if (!(event.getLevel() instanceof ServerLevel)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        int removed = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof PipeBlockEntity pipe)) continue;

            // Collect first: removePipeAndDropContainedItems() mutates the very node set getNodes()
            // returns, so removing while iterating it would be a ConcurrentModificationException.
            List<PipeNetworkType> electricTypes = null;
            for (PipeNetworkNode node : pipe.getNodes()) {
                if (MIPipes.ELECTRICITY_PIPE_TIER.containsKey(node.getType())) {
                    if (electricTypes == null) electricTypes = new ArrayList<>();
                    electricTypes.add(node.getType());
                }
            }
            if (electricTypes == null) continue;

            for (PipeNetworkType type : electricTypes) {
                pipe.removePipeAndDropContainedItems(type);
                removed++;
            }
        }
        if (removed > 0) {
            Mir.LOGGER.info("[mir] Removed {} MI cable connection(s) from chunk {}", removed, chunk.getPos());
        }
    }
}
