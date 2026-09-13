package mirefresh.mir.mi.client;

import aztech.modern_industrialization.machines.models.MachineModelClientData;
import com.mojang.math.Axis;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.QuadTransformers;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wraps a MI machine's baked model and appends the connector geometry, rotated onto the face
 * opposite the machine's front (read from {@link MachineModelClientData#frontDirection} in the
 * per-block {@link ModelData}). Everything except quad output is delegated to the host model.
 *
 * <p>Extra quads are emitted only on the {@code side == null} (non-culled) pass so they always
 * render regardless of the host's culling.
 */
public class ConnectorModelWrapper implements BakedModel {

    /**
     * Carries the connector's placement from {@code MachineBlockEntityClientMixin} into model baking
     * — it's independent of MI's own synced {@link MachineModelClientData} once an edge-zone wrench
     * click has moved it, so it can no longer be read from there alone.
     */
    public static final ModelProperty<Placement> CONNECTOR_FACE = new ModelProperty<>();

    /**
     * Where the connector sits. {@code secondary == null} is a plain face mount (center-zone click,
     * or the pre-edge-click default): {@code primary} is rotated onto via {@link #quadsForFace}, same
     * as before this existed. {@code secondary != null} is an edge-zone mount, one of the 12 physical
     * edges of the block ({@code primary}+{@code secondary} touching, e.g. UP+SOUTH): dispatched
     * straight to one of the twelve pre-authored {@code mi_connector_edge_*} models via
     * {@link #edgeQuads}, with no rotation — those models are placed for that exact edge, not derived
     * by rotating a single canonical shape (rotation of one shape can't reproduce all 12 edges, since
     * they aren't all related by a shared-axis rotation of one another).
     */
    public record Placement(Direction primary, @Nullable Direction secondary) {}

    private final BakedModel wrapped;
    /** Single-model mode (e.g. the storage-unit buffer connector): same shape on every face. */
    @Nullable
    private final List<BakedQuad> canonicalQuads;
    /** Dual-model mode (the plain consumer connector): corner-mounted pair for N/S/E/W... */
    @Nullable
    private final List<BakedQuad> sideQuads;
    /** ...a flat pair for UP/DOWN. Both authored canonically for +Z / south, same as canonicalQuads. */
    @Nullable
    private final List<BakedQuad> topQuads;
    /**
     * Pre-baked quads for each of the 12 physical block edges, keyed by the (unordered) pair of
     * directions that edge touches. Only present in dual-model mode; {@code null} in single-model mode
     * (the buffer connector has no edge-specific models, so it always uses the rotation fallback).
     */
    @Nullable
    private final Map<Set<Direction>, List<BakedQuad>> edgeQuads;
    /**
     * Directly-placed quads for individual faces of the single-model (buffer) connector — e.g. its
     * four horizontal directions, each authored separately rather than derived by rotating one
     * canonical shape (see the {@code mi_connector_buffer_*} models). Only present in single-model
     * mode; any face absent from this map (here, UP/DOWN — no models exist for those) falls back to
     * rotating {@link #canonicalQuads}, unchanged from before this existed.
     */
    @Nullable
    private final Map<Direction, List<BakedQuad>> directFaceQuads;
    /** Lazily-built per-face rotations (the fallback path for whichever faces aren't pre-placed). */
    private final Map<Direction, List<BakedQuad>> rotated = new EnumMap<>(Direction.class);

    /** Single-model mode: identical geometry regardless of which face it lands on. */
    public ConnectorModelWrapper(BakedModel wrapped, List<BakedQuad> canonicalQuads) {
        this(wrapped, canonicalQuads, null);
    }

    /**
     * Single-model mode with some faces directly placed (see {@link #directFaceQuads}); any other
     * face still rotates {@code canonicalQuads} as before.
     */
    public ConnectorModelWrapper(BakedModel wrapped, List<BakedQuad> canonicalQuads,
            @Nullable Map<Direction, List<BakedQuad>> directFaceQuads) {
        this.wrapped = wrapped;
        this.canonicalQuads = canonicalQuads;
        this.sideQuads = null;
        this.topQuads = null;
        this.edgeQuads = null;
        this.directFaceQuads = directFaceQuads;
    }

    /**
     * Dual-model mode: {@code sideQuads} for a horizontal target face, {@code topQuads} for UP/DOWN
     * (both used only as the {@code secondary == null} fallback), {@code edgeQuads} for the 12
     * directly-placed edge mounts.
     */
    public ConnectorModelWrapper(BakedModel wrapped, List<BakedQuad> sideQuads, List<BakedQuad> topQuads,
            Map<Set<Direction>, List<BakedQuad>> edgeQuads) {
        this.wrapped = wrapped;
        this.canonicalQuads = null;
        this.sideQuads = sideQuads;
        this.topQuads = topQuads;
        this.edgeQuads = edgeQuads;
        this.directFaceQuads = null;
    }

    /**
     * Single-model mode with BOTH directly-placed faces AND directly-placed edges (the generator
     * connector: {@code directFaceQuads} for its four horizontal directions, {@code edgeQuads} for
     * its 12 physical-edge mounts, {@code canonicalQuads} — the south model, reused — as the
     * rotation fallback for a plain UP/DOWN face mount, which has no directly-authored model of its
     * own). Unlike the other single-model constructor, this one never leaves {@code directFaceQuads}
     * null, so {@link #quadsForFace} always prefers the direct model on S/W/E/N.
     */
    public ConnectorModelWrapper(BakedModel wrapped, List<BakedQuad> canonicalQuads,
            Map<Direction, List<BakedQuad>> directFaceQuads, Map<Set<Direction>, List<BakedQuad>> edgeQuads) {
        this.wrapped = wrapped;
        this.canonicalQuads = canonicalQuads;
        this.sideQuads = null;
        this.topQuads = null;
        this.edgeQuads = edgeQuads;
        this.directFaceQuads = directFaceQuads;
    }

    /**
     * If set, quads using this sprite are dropped from every {@code wrapped} result before our own
     * geometry is appended — used to strip MI's own "this side outputs energy" decal
     * ({@code modern_industrialization:block/overlays/output_energy}) from blocks we've
     * electrified, since our connector replaces that indicator. {@code null} on blocks whose
     * output decal should stay as MI drew it (anything not electrified, e.g. generators).
     */
    @Nullable
    private TextureAtlasSprite hideSprite;

    public ConnectorModelWrapper hidingSprite(@Nullable TextureAtlasSprite sprite) {
        this.hideSprite = sprite;
        return this;
    }

    private List<BakedQuad> stripHidden(List<BakedQuad> quads) {
        if (hideSprite == null) return quads;
        List<BakedQuad> out = null;
        for (int i = 0; i < quads.size(); i++) {
            boolean hidden = quads.get(i).getSprite() == hideSprite;
            if (hidden && out == null) {
                out = new ArrayList<>(quads.subList(0, i));
            } else if (!hidden && out != null) {
                out.add(quads.get(i));
            }
        }
        return out != null ? out : quads;
    }

    private List<BakedQuad> canonicalFor(Direction face) {
        if (canonicalQuads != null) return canonicalQuads;
        return face == Direction.UP || face == Direction.DOWN ? topQuads : sideQuads;
    }

    /**
     * Resolve the connector's placement. {@link #CONNECTOR_FACE} (set by
     * {@code MachineBlockEntityClientMixin} from {@code MiElectricHolder}'s connector state) is
     * authoritative whenever present. Absent only for the dataless / item-form render path, where it
     * falls back to MI's own {@link MachineModelClientData#outputDirection} /
     * {@link MachineModelClientData#frontDirection} as a plain face mount — matching the old,
     * pre-edge-click behaviour.
     */
    private static Placement placementFor(ModelData data) {
        Placement placement = data.get(CONNECTOR_FACE);
        if (placement != null) return placement;
        MachineModelClientData mi = data.get(MachineModelClientData.KEY);
        Direction face;
        if (mi != null && mi.outputDirection != null) face = mi.outputDirection;
        else if (mi != null && mi.frontDirection != null) face = mi.frontDirection.getOpposite();
        else face = Direction.SOUTH; // canonical (also the dataless / item path)
        return new Placement(face, null);
    }

    private List<BakedQuad> quadsFor(Placement placement) {
        if (placement.secondary() != null && edgeQuads != null) {
            List<BakedQuad> exact = edgeQuads.get(EnumSet.of(placement.primary(), placement.secondary()));
            if (exact != null) return exact;
        }
        return quadsForFace(placement.primary());
    }

    private List<BakedQuad> quadsForFace(Direction face) {
        return rotated.computeIfAbsent(face, f -> {
            if (directFaceQuads != null) {
                List<BakedQuad> direct = directFaceQuads.get(f);
                if (direct != null) return direct;
            }
            List<BakedQuad> canonical = canonicalFor(f);
            if (canonicalQuads == null && (f == Direction.UP || f == Direction.DOWN)) {
                // mi_connector_top.json is authored flush against the top face already (matching
                // MiIntegration.CONSUMER_TOP_TERMINALS's own coordinates) — unlike sideQuads, which is
                // south-canonical. So UP needs no transform at all, and DOWN is a plain 180 flip — not
                // rotationTo(DOWN) below, which is relative to a south-canonical shape and would rotate
                // this already-correctly-placed one onto north/south instead.
                if (f == Direction.UP) return canonical;
                List<BakedQuad> flipped = QuadTransformers.applying(FLIP_UP_DOWN).process(canonical);
                return remapFlipUpDown(flipped);
            }
            if (f == Direction.SOUTH) return canonical; // canonical face, no transform needed
            Transformation t = rotationTo(f);
            List<BakedQuad> transformed = QuadTransformers.applying(t).process(canonical);
            return remapDirections(transformed, f);
        });
    }

    /**
     * {@link QuadTransformers#applying} rotates vertex positions/normals but leaves each quad's
     * declared {@link BakedQuad#getDirection()} at its pre-rotation value — that field (not the
     * geometric normal) is what vanilla's ambient-occlusion / per-face diffuse lighting samples, so
     * without this the connector reads its light level from the wrong neighbouring block and renders
     * visibly darker wherever it happens to sit next to another block. Rebuild each quad with the
     * direction the rotation actually sends it to.
     */
    private static List<BakedQuad> remapDirections(List<BakedQuad> quads, Direction face) {
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad q : quads) {
            Direction mapped = remapDirection(q.getDirection(), face);
            out.add(mapped == q.getDirection() ? q
                    : new BakedQuad(q.getVertices(), q.getTintIndex(), mapped, q.getSprite(), q.isShade(), q.hasAmbientOcclusion()));
        }
        return out;
    }

    /** Same rotation {@link #rotationTo} applies to geometry, expressed on the six face directions. */
    private static Direction remapDirection(Direction original, Direction face) {
        return switch (face) {
            case SOUTH -> original;
            case WEST -> Rotation.CLOCKWISE_90.rotate(original);
            case NORTH -> Rotation.CLOCKWISE_180.rotate(original);
            case EAST -> Rotation.COUNTERCLOCKWISE_90.rotate(original);
            case UP -> rotateAroundEastWestAxis(original, true);
            case DOWN -> rotateAroundEastWestAxis(original, false);
        };
    }

    // Rotation.rotate() only covers the Y axis; UP/DOWN targets rotate about the fixed East-West
    // axis instead, cycling SOUTH<->UP<->NORTH<->DOWN (EAST/WEST are untouched by that axis).
    private static final Direction[] EW_AXIS_CYCLE = {Direction.SOUTH, Direction.UP, Direction.NORTH, Direction.DOWN};

    private static Direction rotateAroundEastWestAxis(Direction d, boolean forward) {
        int i = switch (d) {
            case SOUTH -> 0;
            case UP -> 1;
            case NORTH -> 2;
            case DOWN -> 3;
            default -> -1; // EAST / WEST: unaffected
        };
        return i < 0 ? d : EW_AXIS_CYCLE[Math.floorMod(forward ? i + 1 : i - 1, 4)];
    }

    /** 180-degree flip about the East-West axis, carrying the up-canonical topQuads onto DOWN. */
    private static final Transformation FLIP_UP_DOWN =
            new Transformation(new Vector3f(0.5f, 0.5f, 0.5f), Axis.XP.rotationDegrees(180), null, null)
                    .compose(new Transformation(new Vector3f(-0.5f, -0.5f, -0.5f), null, null, null));

    private static List<BakedQuad> remapFlipUpDown(List<BakedQuad> quads) {
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad q : quads) {
            Direction mapped = switch (q.getDirection()) {
                case UP -> Direction.DOWN;
                case DOWN -> Direction.UP;
                case NORTH -> Direction.SOUTH;
                case SOUTH -> Direction.NORTH;
                case EAST -> Direction.EAST;
                case WEST -> Direction.WEST;
            };
            out.add(mapped == q.getDirection() ? q
                    : new BakedQuad(q.getVertices(), q.getTintIndex(), mapped, q.getSprite(), q.isShade(), q.hasAmbientOcclusion()));
        }
        return out;
    }

    /** Rotation that carries the canonical +Z geometry onto {@code face}, about the block centre. */
    private static Transformation rotationTo(Direction face) {
        // Angles chosen to match MiIntegration#terminal (TerminalBoundingBox.rotateAroundY/X), so the
        // clickable box and the visible post land on the same face for all six orientations.
        var rot = switch (face) {
            case SOUTH -> Axis.YP.rotationDegrees(0);
            case WEST  -> Axis.YP.rotationDegrees(270);
            case NORTH -> Axis.YP.rotationDegrees(180);
            case EAST  -> Axis.YP.rotationDegrees(90);
            case UP    -> Axis.XP.rotationDegrees(-90);
            case DOWN  -> Axis.XP.rotationDegrees(90);
        };
        return new Transformation(new Vector3f(0.5f, 0.5f, 0.5f), rot, null, null)
                .compose(new Transformation(new Vector3f(-0.5f, -0.5f, -0.5f), null, null, null));
    }

    private List<BakedQuad> combine(List<BakedQuad> base, Placement placement) {
        List<BakedQuad> extra = quadsFor(placement);
        List<BakedQuad> out = new ArrayList<>(base.size() + extra.size());
        out.addAll(base);
        out.addAll(extra);
        return out;
    }

    private static final Placement DEFAULT_PLACEMENT = new Placement(Direction.SOUTH, null);

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand) {
        List<BakedQuad> base = stripHidden(wrapped.getQuads(state, side, rand));
        return side != null ? base : combine(base, DEFAULT_PLACEMENT);
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand,
                                             @NotNull ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> base = stripHidden(wrapped.getQuads(state, side, rand, data, renderType));
        if (side != null) return base;
        return combine(base, placementFor(data));
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        return wrapped.getRenderTypes(state, rand, data);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos,
                                           @NotNull BlockState state, @NotNull ModelData modelData) {
        return wrapped.getModelData(level, pos, state, modelData);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return wrapped.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return wrapped.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return wrapped.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return wrapped.isCustomRenderer();
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull TextureAtlasSprite getParticleIcon() {
        return wrapped.getParticleIcon();
    }

    @Override
    public @NotNull TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
        return wrapped.getParticleIcon(data);
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull ItemTransforms getTransforms() {
        return wrapped.getTransforms();
    }

    @Override
    public @NotNull ItemOverrides getOverrides() {
        return wrapped.getOverrides();
    }
}
