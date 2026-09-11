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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a MI machine's baked model and appends the connector geometry, rotated onto the face
 * opposite the machine's front (read from {@link MachineModelClientData#frontDirection} in the
 * per-block {@link ModelData}). Everything except quad output is delegated to the host model.
 *
 * <p>Extra quads are emitted only on the {@code side == null} (non-culled) pass so they always
 * render regardless of the host's culling.
 */
public class ConnectorModelWrapper implements BakedModel {

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
    /** Lazily-built per-face rotations. */
    private final Map<Direction, List<BakedQuad>> rotated = new EnumMap<>(Direction.class);

    /** Single-model mode: identical geometry regardless of which face it lands on. */
    public ConnectorModelWrapper(BakedModel wrapped, List<BakedQuad> canonicalQuads) {
        this.wrapped = wrapped;
        this.canonicalQuads = canonicalQuads;
        this.sideQuads = null;
        this.topQuads = null;
    }

    /** Dual-model mode: {@code sideQuads} for a horizontal target face, {@code topQuads} for UP/DOWN. */
    public ConnectorModelWrapper(BakedModel wrapped, List<BakedQuad> sideQuads, List<BakedQuad> topQuads) {
        this.wrapped = wrapped;
        this.canonicalQuads = null;
        this.sideQuads = sideQuads;
        this.topQuads = topQuads;
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
     * Resolve which block face the connector sits on. Storage units / hatches have no "front",
     * only an {@link MachineModelClientData#outputDirection} (wrench-configurable) — that wins.
     * Plain machines fall back to the face opposite their {@link MachineModelClientData#frontDirection}.
     */
    private static Direction faceFor(@Nullable MachineModelClientData mi) {
        if (mi != null && mi.outputDirection != null) return mi.outputDirection;
        if (mi != null && mi.frontDirection != null) return mi.frontDirection.getOpposite();
        return Direction.SOUTH; // canonical (also the dataless / item path)
    }

    private List<BakedQuad> quadsForFace(Direction face) {
        return rotated.computeIfAbsent(face, f -> {
            List<BakedQuad> canonical = canonicalFor(f);
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

    private List<BakedQuad> combine(List<BakedQuad> base, Direction face) {
        List<BakedQuad> extra = quadsForFace(face);
        List<BakedQuad> out = new ArrayList<>(base.size() + extra.size());
        out.addAll(base);
        out.addAll(extra);
        return out;
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand) {
        List<BakedQuad> base = stripHidden(wrapped.getQuads(state, side, rand));
        return side != null ? base : combine(base, Direction.SOUTH);
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand,
                                             @NotNull ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> base = stripHidden(wrapped.getQuads(state, side, rand, data, renderType));
        if (side != null) return base;
        return combine(base, faceFor(data.get(MachineModelClientData.KEY)));
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
