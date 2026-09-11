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

    // Canonical terminal boxes, authored for the +Z (south) face to match mir:block/mi_connector.
    // The whole 5-pin assembly rotates together to the machine's output face (see #terminal): the
    // OUTPUT trio sits on that face, the INPUT pair rides along on the block's bottom edge (a
    // horizontal output rotation about Y leaves the -Y group on the bottom).
    //
    // OUTPUT (indices 0..2), match MiConnectorCircuit terminalNode(0/1/2):
    //   0 = POSITIVE (x 10-12), 1 = NEGATIVE (x 7-9), 2 = CONTROL (x 4-6, throttle — as on the FE inverter).
    // INPUT (indices 3..4), match terminalNode(3/4): 3 = POSITIVE (x 10-12), 4 = NEGATIVE (x 7-9),
    //   both on the -Y posts (y -1..2).
    // .withOrigin(...) (1/16-block units) pins the wire's attach point to the outer tip of each
    // post; without it the anchor defaults to the box centre, which sits at the block surface and
    // makes the wire look half-buried. Output posts point +Z (tip z=17), input posts point -Y
    // (tip y=-1).
    private static final TerminalBoundingBox[] OUTPUT_TERMINALS = {
            new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 10, 14, 14, 12, 17, 17).withColor(IDecoratedTerminal.RED).withOrigin(11, 15.5, 17),
            new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 7, 14, 14, 9, 17, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(8, 15.5, 17),
            new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 4, 14, 14, 6, 17, 17).withColor(IDecoratedTerminal.GREEN).withOrigin(5, 15.5, 17),
    };
    private static final TerminalBoundingBox[] INPUT_TERMINALS = {
            new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 10, -1, 14, 12, 2, 17).withColor(IDecoratedTerminal.RED).withOrigin(11, -1, 15.5),
            new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 7, -1, 14, 9, 2, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(8, -1, 15.5),
    };

    // The 2-pin consumer connector (mirefresh.mir.mi client model mi_connector_side/mi_connector_top)
    // uses a DIFFERENT canonical shape depending on which target face it lands on: a corner-mounted
    // pair for the four horizontal faces, a flat pair for UP/DOWN — see #consumerTerminal.
    private static final TerminalBoundingBox[] CONSUMER_SIDE_TERMINALS = {
            new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 14, 5, 14, 17, 7, 17).withColor(IDecoratedTerminal.RED).withOrigin(17, 6, 17),
            new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 14, 9, 14, 17, 11, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(17, 10, 17),
    };
    private static final TerminalBoundingBox[] CONSUMER_TOP_TERMINALS = {
            new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 9, 14, 14, 11, 17, 17).withColor(IDecoratedTerminal.RED).withOrigin(10, 15.5, 17),
            new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 5, 14, 14, 7, 17, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(6, 15.5, 17),
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

    /** Number of connector terminals (output trio + input pair). */
    public static final int TERMINAL_COUNT = OUTPUT_TERMINALS.length + INPUT_TERMINALS.length;

    /**
     * Terminal {@code index} (0..4) oriented so the whole connector assembly sits with its output
     * trio on {@code face}. Indices 0..2 are the output +/&minus;/CONTROL posts, 3..4 the input
     * +/&minus; posts. The same rotation is applied to every box so it stays in step with
     * {@code ConnectorModelWrapper#rotationTo}, which rotates the whole model.
     */
    public static TerminalBoundingBox terminal(int index, Direction face) {
        TerminalBoundingBox t;
        if (index >= 0 && index < OUTPUT_TERMINALS.length) {
            t = OUTPUT_TERMINALS[index];
        } else if (index >= OUTPUT_TERMINALS.length && index < TERMINAL_COUNT) {
            t = INPUT_TERMINALS[index - OUTPUT_TERMINALS.length];
        } else {
            return null;
        }
        return switch (face) {
            case SOUTH -> t;
            case NORTH -> t.rotateAroundY(180);
            case EAST -> t.rotateAroundY(270);
            case WEST -> t.rotateAroundY(90);
            case UP -> t.rotateAroundX(90);
            case DOWN -> t.rotateAroundX(-90);
        };
    }

    /** Number of consumer-connector terminals (+/- only, no CONTROL). */
    public static final int CONSUMER_TERMINAL_COUNT = CONSUMER_SIDE_TERMINALS.length;

    /**
     * Consumer-connector terminal {@code index} (0/1) oriented onto {@code face}. Picks the
     * corner-mounted canonical shape for the four horizontal faces or the flat one for UP/DOWN
     * (matching {@code ConnectorModelWrapper}'s model choice) before applying the same rotation as
     * {@link #terminal}.
     */
    public static TerminalBoundingBox consumerTerminal(int index, Direction face) {
        if (index < 0 || index >= CONSUMER_TERMINAL_COUNT) return null;
        boolean vertical = face == Direction.UP || face == Direction.DOWN;
        TerminalBoundingBox t = (vertical ? CONSUMER_TOP_TERMINALS : CONSUMER_SIDE_TERMINALS)[index];
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
