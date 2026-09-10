package mirefresh.mir.mi;

import mirefresh.mir.Mir;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Runtime glue for the MI connector bridge: the set of electrified MI blocks, the connector's
 * terminal geometry, and a level-tick pump that drives + tears down the hidden
 * {@link MiElectricCompanion}s. MI's own tick is never used, so this works regardless of which MI
 * machine subclass ticks (or whether it ticks at all).
 */
@EventBusSubscriber(modid = Mir.MODID)
public final class MiIntegration {

    /** MI blocks that get a connector + companion. Starts with the energy storage units. */
    public static final Set<ResourceLocation> ELECTRIFIED = Set.of(
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "lv_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "mv_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "hv_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "ev_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "superconductor_storage_unit"),
            ResourceLocation.fromNamespaceAndPath("modern_industrialization", "creative_storage_unit")
    );

    /** Live companions, ticked from {@link #onLevelTick}. Weakly held: the owning MI BE keeps the
     * strong reference for as long as it is alive. */
    private static final Set<MiElectricCompanion> ACTIVE =
            Collections.newSetFromMap(new WeakHashMap<>());

    // Canonical terminal boxes, authored for the +Z (south) face to match mir:block/mi_connector's
    // three posts. Index order must match ElectricLoad.attach: 0 = POSITIVE (x 10-12),
    // 1 = NEGATIVE (x 7-9), 2 = CONTROL (x 4-6, throttle — as on the FE inverter).
    private static final TerminalBoundingBox[] TERMINALS = {
            new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 10, 14, 14, 12, 17, 17).withColor(IDecoratedTerminal.RED),
            new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 7, 14, 14, 9, 17, 17).withColor(IDecoratedTerminal.BLUE),
            new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 4, 14, 14, 6, 17, 17).withColor(IDecoratedTerminal.GREEN),
    };

    private MiIntegration() {}

    public static void addCompanion(MiElectricCompanion c) {
        ACTIVE.add(c);
    }

    public static void removeCompanion(MiElectricCompanion c) {
        ACTIVE.remove(c);
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        // Runs on both sides: server companions simulate, client companions only exist so the
        // wire-attach handshake resolves a non-null behaviour. Each tick pass filters to the
        // companions whose getLevel() matches this event's level.
        for (MiElectricCompanion c : ACTIVE.toArray(new MiElectricCompanion[0])) {
            if (c.getLevel() != level) continue;
            BlockPos pos = c.getBlockPos();

            if (!level.hasChunkAt(pos)) {
                // Chunk unloaded out from under us. Pause + detach but DON'T break wire
                // connections — the companion is rebuilt (and re-joins the grid) on chunk reload
                // via MachineBlockEntityMixin#getBehaviour.
                if (!c.isRemoved()) {
                    c.onChunkUnloaded();
                    c.setRemoved();
                }
                ACTIVE.remove(c);
                continue;
            }

            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof MiElectricHolder holder) || holder.mir$peekCompanion() != c) {
                // MI block was broken or replaced — full teardown, drops any attached wire.
                if (!c.isRemoved()) c.setRemoved();
                ACTIVE.remove(c);
                continue;
            }

            if (c.isRemoved()) {
                ACTIVE.remove(c);
                continue;
            }
            c.tick();
        }
    }

    /**
     * Terminal {@code index} (0/1/2) oriented so the connector sits on {@code face}.
     *
     * <p>Face &rarr; rotation must land the box on the named face; it is kept in step with
     * {@code ConnectorModelWrapper#rotationTo} so the clickable box and the visible post coincide.
     * (N/S agree by construction; E/W and U/D alignment should be spot-checked in game against the
     * model overlay.)
     */
    public static TerminalBoundingBox terminal(int index, Direction face) {
        if (index < 0 || index >= TERMINALS.length) return null;
        TerminalBoundingBox t = TERMINALS[index];
        return switch (face) {
            case SOUTH -> t;
            case NORTH -> t.rotateAroundY(180);
            case EAST -> t.rotateAroundY(270);
            case WEST -> t.rotateAroundY(90);
            case UP -> t.rotateAroundX(90);
            case DOWN -> t.rotateAroundX(-90);
        };
    }
}
