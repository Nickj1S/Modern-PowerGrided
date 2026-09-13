package mirefresh.mir.mi;

import aztech.modern_industrialization.machines.blockentities.GeneratorMachineBlockEntity;
import mirefresh.mir.Mir;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
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

    // Terminal hitboxes for the 12 physical-edge consumer-connector mounts, one pair per edge,
    // authored directly from each mi_connector_edge_* model's own pin coordinates (see
    // MiConnectorModels) rather than derived by rotating CONSUMER_SIDE_TERMINALS/CONSUMER_TOP_TERMINALS
    // — the 12 edges aren't all related by a shared-axis rotation of one another, which is exactly why
    // the visual models are placed directly too. Keyed by the (unordered) pair of directions that
    // physical edge touches, matching how #consumerTerminal looks them up.
    private static final Map<Set<Direction>, TerminalBoundingBox[]> CONSUMER_EDGE_TERMINALS = Map.ofEntries(
            Map.entry(EnumSet.of(Direction.SOUTH, Direction.EAST), CONSUMER_SIDE_TERMINALS), // identical geometry
            Map.entry(EnumSet.of(Direction.SOUTH, Direction.WEST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, -1, 5, 14, 2, 7, 17).withColor(IDecoratedTerminal.RED).withOrigin(-1, 6, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, -1, 9, 14, 2, 11, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(-1, 10, 17),
            }),
            Map.entry(EnumSet.of(Direction.NORTH, Direction.WEST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, -1, 5, -1, 2, 7, 2).withColor(IDecoratedTerminal.RED).withOrigin(-1, 6, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, -1, 9, -1, 2, 11, 2).withColor(IDecoratedTerminal.BLUE).withOrigin(-1, 10, -1),
            }),
            Map.entry(EnumSet.of(Direction.NORTH, Direction.EAST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 14, 5, -1, 17, 7, 2).withColor(IDecoratedTerminal.RED).withOrigin(17, 6, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 14, 9, -1, 17, 11, 2).withColor(IDecoratedTerminal.BLUE).withOrigin(17, 10, -1),
            }),
            Map.entry(EnumSet.of(Direction.UP, Direction.SOUTH), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 9, 14, 14, 11, 17, 17).withColor(IDecoratedTerminal.RED).withOrigin(10, 17, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 5, 14, 14, 7, 17, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(6, 17, 17),
            }),
            Map.entry(EnumSet.of(Direction.UP, Direction.NORTH), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 5, 14, -1, 7, 17, 2).withColor(IDecoratedTerminal.RED).withOrigin(6, 17, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 9, 14, -1, 11, 17, 2).withColor(IDecoratedTerminal.BLUE).withOrigin(10, 17, -1),
            }),
            Map.entry(EnumSet.of(Direction.UP, Direction.EAST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 14, 14, 5, 17, 17, 7).withColor(IDecoratedTerminal.RED).withOrigin(17, 17, 6),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 14, 14, 9, 17, 17, 11).withColor(IDecoratedTerminal.BLUE).withOrigin(17, 17, 10),
            }),
            Map.entry(EnumSet.of(Direction.UP, Direction.WEST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, -1, 14, 9, 2, 17, 11).withColor(IDecoratedTerminal.RED).withOrigin(-1, 17, 10),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, -1, 14, 5, 2, 17, 7).withColor(IDecoratedTerminal.BLUE).withOrigin(-1, 17, 6),
            }),
            Map.entry(EnumSet.of(Direction.DOWN, Direction.SOUTH), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 5, -1, 14, 7, 2, 17).withColor(IDecoratedTerminal.RED).withOrigin(6, -1, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 9, -1, 14, 11, 2, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(10, -1, 17),
            }),
            Map.entry(EnumSet.of(Direction.DOWN, Direction.NORTH), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 9, -1, -1, 11, 2, 2).withColor(IDecoratedTerminal.RED).withOrigin(10, -1, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 5, -1, -1, 7, 2, 2).withColor(IDecoratedTerminal.BLUE).withOrigin(6, -1, -1),
            }),
            Map.entry(EnumSet.of(Direction.DOWN, Direction.EAST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 14, -1, 9, 17, 2, 11).withColor(IDecoratedTerminal.RED).withOrigin(17, -1, 10),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 14, -1, 5, 17, 2, 7).withColor(IDecoratedTerminal.BLUE).withOrigin(17, -1, 6),
            }),
            Map.entry(EnumSet.of(Direction.DOWN, Direction.WEST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, -1, -1, 5, 2, 2, 7).withColor(IDecoratedTerminal.RED).withOrigin(-1, -1, 6),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, -1, -1, 9, 2, 2, 11).withColor(IDecoratedTerminal.BLUE).withOrigin(-1, -1, 10),
            })
    );

    // Terminal hitboxes for the buffer (storage-unit) connector's four horizontal directions,
    // authored directly from each mi_connector_buffer_* model's own pin coordinates (see
    // MiConnectorModels) rather than rotated from a single south-canonical shape — same reasoning as
    // CONSUMER_EDGE_TERMINALS above. Index order matches #terminal: 0=output POSITIVE, 1=output
    // NEGATIVE, 2=output CONTROL, 3=input POSITIVE, 4=input NEGATIVE. No UP/DOWN entries: the buffer
    // connector has no vertical position (see MachineBlockEntityMixin#mir$connectorFace(), which
    // clamps to SOUTH before #terminal is ever called with a vertical face).
    private static final Map<Direction, TerminalBoundingBox[]> BUFFER_DIRECT_TERMINALS = Map.of(
            Direction.SOUTH, new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 11, 14, 14, 13, 17, 17).withColor(IDecoratedTerminal.RED).withOrigin(12, 15.5, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 7, 14, 14, 9, 17, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(8, 15.5, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 3, 14, 14, 5, 17, 17).withColor(IDecoratedTerminal.GREEN).withOrigin(4, 15.5, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 9, -1, 14, 11, 2, 17).withColor(IDecoratedTerminal.RED).withOrigin(10, -1, 15.5),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 5, -1, 14, 7, 2, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(6, -1, 15.5),
            },
            Direction.WEST, new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, -1, 14, 11, 2, 17, 13).withColor(IDecoratedTerminal.RED).withOrigin(-1, 15.5, 12),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, -1, 14, 7, 2, 17, 9).withColor(IDecoratedTerminal.BLUE).withOrigin(-1, 15.5, 8),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, -1, 14, 3, 2, 17, 5).withColor(IDecoratedTerminal.GREEN).withOrigin(-1, 15.5, 4),
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, -1, -1, 9, 2, 2, 11).withColor(IDecoratedTerminal.RED).withOrigin(0.5, -1, 10),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, -1, -1, 5, 2, 2, 7).withColor(IDecoratedTerminal.BLUE).withOrigin(0.5, -1, 6),
            },
            Direction.EAST, new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 14, 14, 3, 17, 17, 5).withColor(IDecoratedTerminal.RED).withOrigin(17, 15.5, 4),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 14, 14, 7, 17, 17, 9).withColor(IDecoratedTerminal.BLUE).withOrigin(17, 15.5, 8),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 14, 14, 11, 17, 17, 13).withColor(IDecoratedTerminal.GREEN).withOrigin(17, 15.5, 12),
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 14, -1, 5, 17, 2, 7).withColor(IDecoratedTerminal.RED).withOrigin(15.5, -1, 6),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 14, -1, 9, 17, 2, 11).withColor(IDecoratedTerminal.BLUE).withOrigin(15.5, -1, 10),
            },
            Direction.NORTH, new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 3, 14, -1, 5, 17, 2).withColor(IDecoratedTerminal.RED).withOrigin(4, 15.5, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 7, 14, -1, 9, 17, 2).withColor(IDecoratedTerminal.BLUE).withOrigin(8, 15.5, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 11, 14, -1, 13, 17, 2).withColor(IDecoratedTerminal.GREEN).withOrigin(12, 15.5, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 5, -1, -1, 7, 2, 2).withColor(IDecoratedTerminal.RED).withOrigin(6, -1, 0.5),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 9, -1, -1, 11, 2, 2).withColor(IDecoratedTerminal.BLUE).withOrigin(10, -1, 0.5),
            }
    );

    // Terminal hitboxes for the generator connector's four horizontal directions: just the output
    // trio (POSITIVE/NEGATIVE/CONTROL, index 0..2) — a generator has no input group at all. Reuses
    // BUFFER_DIRECT_TERMINALS' own trio coordinates rather than re-deriving them, since the flat
    // generator connector models (mi_generator_connector_south/west/east/north.json) were themselves
    // derived by taking exactly the buffer connector models' first 3 elements (see MiConnectorModels).
    private static final Map<Direction, TerminalBoundingBox[]> GENERATOR_DIRECT_TERMINALS;
    static {
        Map<Direction, TerminalBoundingBox[]> m = new EnumMap<>(Direction.class);
        for (Direction dir : new Direction[] {Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.NORTH}) {
            m.put(dir, Arrays.copyOfRange(BUFFER_DIRECT_TERMINALS.get(dir), 0, 3));
        }
        GENERATOR_DIRECT_TERMINALS = Collections.unmodifiableMap(m);
    }

    // Terminal hitboxes for the 12 physical-edge generator-connector mounts (see
    // mi_generator_connector_edge_*.json in MiConnectorModels), authored directly from each edge
    // model's own pin coordinates — same reasoning as CONSUMER_EDGE_TERMINALS. Each edge gets the
    // full output trio (POSITIVE/NEGATIVE/CONTROL); no input pair.
    private static final Map<Set<Direction>, TerminalBoundingBox[]> GENERATOR_EDGE_TERMINALS = Map.ofEntries(
            Map.entry(EnumSet.of(Direction.UP, Direction.SOUTH), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 11, 14, 14, 13, 17, 17).withColor(IDecoratedTerminal.RED).withOrigin(12, 17, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 7, 14, 14, 9, 17, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(8, 17, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 3, 14, 14, 5, 17, 17).withColor(IDecoratedTerminal.GREEN).withOrigin(4, 17, 17),
            }),
            Map.entry(EnumSet.of(Direction.UP, Direction.NORTH), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 3, 14, -1, 5, 17, 2).withColor(IDecoratedTerminal.RED).withOrigin(4, 17, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 7, 14, -1, 9, 17, 2).withColor(IDecoratedTerminal.BLUE).withOrigin(8, 17, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 11, 14, -1, 13, 17, 2).withColor(IDecoratedTerminal.GREEN).withOrigin(12, 17, -1),
            }),
            Map.entry(EnumSet.of(Direction.UP, Direction.EAST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 14, 14, 3, 17, 17, 5).withColor(IDecoratedTerminal.RED).withOrigin(17, 17, 4),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 14, 14, 7, 17, 17, 9).withColor(IDecoratedTerminal.BLUE).withOrigin(17, 17, 8),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 14, 14, 11, 17, 17, 13).withColor(IDecoratedTerminal.GREEN).withOrigin(17, 17, 12),
            }),
            Map.entry(EnumSet.of(Direction.UP, Direction.WEST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, -1, 14, 11, 2, 17, 13).withColor(IDecoratedTerminal.RED).withOrigin(-1, 17, 12),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, -1, 14, 7, 2, 17, 9).withColor(IDecoratedTerminal.BLUE).withOrigin(-1, 17, 8),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, -1, 14, 3, 2, 17, 5).withColor(IDecoratedTerminal.GREEN).withOrigin(-1, 17, 4),
            }),
            Map.entry(EnumSet.of(Direction.DOWN, Direction.SOUTH), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 3, -1, 14, 5, 2, 17).withColor(IDecoratedTerminal.RED).withOrigin(4, -1, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 7, -1, 14, 9, 2, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(8, -1, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 11, -1, 14, 13, 2, 17).withColor(IDecoratedTerminal.GREEN).withOrigin(12, -1, 17),
            }),
            Map.entry(EnumSet.of(Direction.DOWN, Direction.NORTH), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 11, -1, -1, 13, 2, 2).withColor(IDecoratedTerminal.RED).withOrigin(12, -1, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 7, -1, -1, 9, 2, 2).withColor(IDecoratedTerminal.BLUE).withOrigin(8, -1, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 3, -1, -1, 5, 2, 2).withColor(IDecoratedTerminal.GREEN).withOrigin(4, -1, -1),
            }),
            Map.entry(EnumSet.of(Direction.DOWN, Direction.EAST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 14, -1, 11, 17, 2, 13).withColor(IDecoratedTerminal.RED).withOrigin(17, -1, 12),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 14, -1, 7, 17, 2, 9).withColor(IDecoratedTerminal.BLUE).withOrigin(17, -1, 8),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 14, -1, 3, 17, 2, 5).withColor(IDecoratedTerminal.GREEN).withOrigin(17, -1, 4),
            }),
            Map.entry(EnumSet.of(Direction.DOWN, Direction.WEST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, -1, -1, 3, 2, 2, 5).withColor(IDecoratedTerminal.RED).withOrigin(-1, -1, 4),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, -1, -1, 7, 2, 2, 9).withColor(IDecoratedTerminal.BLUE).withOrigin(-1, -1, 8),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, -1, -1, 11, 2, 2, 13).withColor(IDecoratedTerminal.GREEN).withOrigin(-1, -1, 12),
            }),
            Map.entry(EnumSet.of(Direction.SOUTH, Direction.EAST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 14, 3, 14, 17, 5, 17).withColor(IDecoratedTerminal.RED).withOrigin(17, 4, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 14, 7, 14, 17, 9, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(17, 8, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 14, 11, 14, 17, 13, 17).withColor(IDecoratedTerminal.GREEN).withOrigin(17, 12, 17),
            }),
            Map.entry(EnumSet.of(Direction.SOUTH, Direction.WEST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, -1, 3, 14, 2, 5, 17).withColor(IDecoratedTerminal.RED).withOrigin(-1, 4, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, -1, 7, 14, 2, 9, 17).withColor(IDecoratedTerminal.BLUE).withOrigin(-1, 8, 17),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, -1, 11, 14, 2, 13, 17).withColor(IDecoratedTerminal.GREEN).withOrigin(-1, 12, 17),
            }),
            Map.entry(EnumSet.of(Direction.NORTH, Direction.WEST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, -1, 3, -1, 2, 5, 2).withColor(IDecoratedTerminal.RED).withOrigin(-1, 4, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, -1, 7, -1, 2, 9, 2).withColor(IDecoratedTerminal.BLUE).withOrigin(-1, 8, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, -1, 11, -1, 2, 13, 2).withColor(IDecoratedTerminal.GREEN).withOrigin(-1, 12, -1),
            }),
            Map.entry(EnumSet.of(Direction.NORTH, Direction.EAST), new TerminalBoundingBox[] {
                    new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 14, 3, -1, 17, 5, 2).withColor(IDecoratedTerminal.RED).withOrigin(17, 4, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 14, 7, -1, 17, 9, 2).withColor(IDecoratedTerminal.BLUE).withOrigin(17, 8, -1),
                    new TerminalBoundingBox(IDecoratedTerminal.CONTROL, 14, 11, -1, 17, 13, 2).withColor(IDecoratedTerminal.GREEN).withOrigin(17, 12, -1),
            })
    );

    @Nullable
    private static Set<ResourceLocation> generatorIds;

    /**
     * Every {@code modern_industrialization}-namespaced block whose block-entity type produces a
     * {@link GeneratorMachineBlockEntity} instance. Found the same way {@code MiConnectorModels}'s
     * client-only {@code findConsumerBlocks()} probes consumer eligibility (a disposable probe BE per
     * candidate block, since no world exists yet at model-bake time) — but placed here in the common
     * package since both the server-safe {@link MiElectricCompanion} and the client-only
     * {@code MiConnectorModels} need this same classification. Lazily computed and cached: the
     * block-entity-type registry is stable once modloading finishes.
     */
    public static Set<ResourceLocation> generatorIds() {
        if (generatorIds == null) {
            Set<ResourceLocation> found = new HashSet<>();
            for (ResourceLocation typeId : BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet()) {
                if (!"modern_industrialization".equals(typeId.getNamespace())) continue;
                BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(typeId);
                if (type == null) continue;

                for (Block block : type.getValidBlocks()) {
                    try {
                        BlockEntity probe = type.create(BlockPos.ZERO, block.defaultBlockState());
                        if (probe instanceof GeneratorMachineBlockEntity) {
                            found.add(BuiltInRegistries.BLOCK.getKey(block));
                        }
                    } catch (Exception e) {
                        Mir.LOGGER.debug("[mir] couldn't probe {} for generator status, skipping", typeId, e);
                    }
                }
            }
            generatorIds = Set.copyOf(found);
        }
        return generatorIds;
    }

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
    public static final int TERMINAL_COUNT = BUFFER_DIRECT_TERMINALS.get(Direction.SOUTH).length;

    /**
     * Terminal {@code index} (0..4): output +/&minus;/CONTROL (0..2), then input +/&minus; (3..4),
     * from the direct per-direction model in {@link #BUFFER_DIRECT_TERMINALS} (matching
     * {@code ConnectorModelWrapper}'s direct per-direction model — no rotation). The buffer connector
     * has no vertical position, so {@code face} is never UP/DOWN here — see
     * {@code MachineBlockEntityMixin#mir$connectorFace()}, which clamps it to SOUTH before this is
     * ever called.
     */
    public static TerminalBoundingBox terminal(int index, Direction face) {
        TerminalBoundingBox[] direct = BUFFER_DIRECT_TERMINALS.get(face);
        return direct != null && index >= 0 && index < direct.length ? direct[index] : null;
    }

    /** Number of consumer-connector terminals (+/- only, no CONTROL). */
    public static final int CONSUMER_TERMINAL_COUNT = CONSUMER_SIDE_TERMINALS.length;

    /**
     * Consumer-connector terminal {@code index} (0/1). When {@code edge} is set, {@code face}+
     * {@code edge} name one of the 12 physical block edges and the box comes straight from
     * {@link #CONSUMER_EDGE_TERMINALS} (matching {@code ConnectorModelWrapper}'s direct model
     * dispatch — no rotation). Otherwise falls back to the old plain-face behaviour: the
     * corner-mounted canonical shape for the four horizontal faces or the flat one for UP/DOWN,
     * rotated the same as {@link #terminal}.
     */
    public static TerminalBoundingBox consumerTerminal(int index, Direction face, @Nullable Direction edge) {
        if (index < 0 || index >= CONSUMER_TERMINAL_COUNT) return null;
        if (edge != null) {
            TerminalBoundingBox[] edgeBoxes = CONSUMER_EDGE_TERMINALS.get(EnumSet.of(face, edge));
            if (edgeBoxes != null) return edgeBoxes[index];
        }
        if (face == Direction.UP || face == Direction.DOWN) {
            // CONSUMER_TOP_TERMINALS is authored up-canonical (its coordinates already sit flush
            // against the top face, matching mi_connector_top.json's own geometry) — unlike
            // CONSUMER_SIDE_TERMINALS, which is south-canonical. So UP needs no rotation at all, and
            // DOWN is a plain 180 flip — not the south-relative rotateAroundX(+-90) used below, which
            // would rotate this already-correctly-placed shape onto north/south instead.
            TerminalBoundingBox t = CONSUMER_TOP_TERMINALS[index];
            return face == Direction.UP ? t : t.rotateAroundX(180);
        }
        TerminalBoundingBox t = CONSUMER_SIDE_TERMINALS[index];
        return switch (face) {
            case SOUTH -> t;
            case NORTH -> t.rotateAroundY(180);
            case EAST -> t.rotateAroundY(270);
            case WEST -> t.rotateAroundY(90);
            default -> null; // UP/DOWN handled above
        };
    }

    /** Number of generator-connector terminals (output trio: +/&minus;/CONTROL, no input pair). */
    public static final int GENERATOR_TERMINAL_COUNT = GENERATOR_DIRECT_TERMINALS.get(Direction.SOUTH).length;

    /**
     * Generator-connector terminal {@code index} (0..2: output +/&minus;/CONTROL). When
     * {@code edge} is set, {@code face}+{@code edge} name one of the 12 physical block edges and
     * the box comes straight from {@link #GENERATOR_EDGE_TERMINALS} (matching
     * {@code ConnectorModelWrapper}'s direct edge-model dispatch — no rotation). For the four plain
     * horizontal faces (no edge), the box comes straight from {@link #GENERATOR_DIRECT_TERMINALS}.
     * A plain UP/DOWN mount (no edge — a flat wrench click straight onto the top/bottom face) has no
     * directly-authored model, so it falls back to the SOUTH trio rotated onto that face, the same
     * way the consumer connector's UP/DOWN fallback works.
     */
    public static TerminalBoundingBox generatorTerminal(int index, Direction face, @Nullable Direction edge) {
        if (index < 0 || index >= GENERATOR_TERMINAL_COUNT) return null;
        if (edge != null) {
            TerminalBoundingBox[] edgeBoxes = GENERATOR_EDGE_TERMINALS.get(EnumSet.of(face, edge));
            if (edgeBoxes != null) return edgeBoxes[index];
        }
        TerminalBoundingBox[] direct = GENERATOR_DIRECT_TERMINALS.get(face);
        if (direct != null) return direct[index];
        if (face == Direction.UP || face == Direction.DOWN) {
            TerminalBoundingBox t = GENERATOR_DIRECT_TERMINALS.get(Direction.SOUTH)[index];
            return t.rotateAroundX(face == Direction.UP ? 90 : -90);
        }
        return null;
    }
}
