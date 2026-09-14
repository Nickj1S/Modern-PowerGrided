package mirefresh.mir.mi.mixin;

import aztech.modern_industrialization.client.machines.MachineOverlayClient;
import aztech.modern_industrialization.client.thirdparty.fabricrendering.QuadBuffer;
import aztech.modern_industrialization.client.thirdparty.fabricrendering.QuadEmitter;
import aztech.modern_industrialization.client.util.RenderHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mirefresh.mir.mi.MiElectricHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MI's wrench hover overlay ({@code MachineOverlayClient#onBlockOutline}) draws a 3x3 grid over the
 * hovered face: a green tint on the cell under the cursor, a faint blue tint on the other eight —
 * with no distinction between the center/corner cells (which still drive MI's own
 * outputDirection/facingDirection, see {@code MachineBlockEntityMixin#mir$useWrenchOnConnector}) and
 * the four edge-midpoint cells (which move the connector instead). This overrides the hovered cell's
 * color to a fixed tint when it's one of those four edge cells on an electrified block.
 *
 * <p>Plain Sponge Mixin can't capture {@code onBlockOutline}'s locals (the loop indices, the
 * resolved {@code MachineBlockEntity}) for a {@code @Redirect}/{@code @ModifyVariable} handler —
 * only MixinExtras' {@code @WrapOperation}/{@code @Local} support that, and this project doesn't
 * depend on it. Instead, three independent redirects each read only what their own target call
 * already receives as arguments, passing the pieces of context forward via {@code @Unique} statics
 * (safe: {@code onBlockOutline} runs to completion synchronously on the render thread before it can
 * be re-entered):
 * <ol>
 *   <li>{@code ClientLevel#getBlockEntity} — is the targeted block electrified, and is it
 *       specifically a buffer (storage unit)?</li>
 *   <li>{@code QuadBuffer#square} — is *this* cell's own bounding box an edge cell (its width along
 *       exactly one of the two face axes equals the 0.5-wide middle band, the other the 0.25-wide
 *       side band), and which face is this 3x3 grid drawn on?</li>
 *   <li>{@code RenderHelper#quadWithAlpha} — is the incoming color the "hovered" one (green, i.e.
 *       {@code g > 0.5f})? If so, and the cell is an edge cell, and this isn't a buffer's UP/DOWN
 *       face (an edge click there is always rejected — see
 *       {@code MachineBlockEntityMixin#mir$useWrenchOnConnector} — so highlighting it as actionable
 *       would be misleading), swap the color; otherwise pass through unchanged.</li>
 * </ol>
 */
@Mixin(MachineOverlayClient.class)
public abstract class MachineOverlayClientMixin {

    @Unique private static final float EDGE_ZONE_R = 0xd6 / 255f;
    @Unique private static final float EDGE_ZONE_G = 0x7b / 255f;
    @Unique private static final float EDGE_ZONE_B = 0x5b / 255f;

    @Unique private static boolean mir$targetElectrified;
    @Unique private static boolean mir$targetIsBuffer;
    @Unique private static boolean mir$cellIsEdge;
    @Unique private static Direction mir$cellFace;

    @Redirect(method = "onBlockOutline", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockEntity("
                    + "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private static BlockEntity mir$captureElectrified(ClientLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        mir$targetElectrified = be instanceof MiElectricHolder holder && holder.mir$electrified();
        mir$targetIsBuffer = be instanceof MiElectricHolder holder && holder.mir$isBuffer();
        return be;
    }

    @Redirect(method = "onBlockOutline", require = 0, at = @At(value = "INVOKE",
            target = "Laztech/modern_industrialization/client/thirdparty/fabricrendering/QuadBuffer;"
                    + "square(Lnet/minecraft/core/Direction;FFFFF)"
                    + "Laztech/modern_industrialization/client/thirdparty/fabricrendering/QuadEmitter;"))
    private static QuadEmitter mir$captureCellShape(QuadBuffer emitter, Direction direction,
            float minX, float minY, float maxX, float maxY, float offset) {
        boolean xMid = Math.abs((maxX - minX) - 0.5f) < 0.01f;
        boolean yMid = Math.abs((maxY - minY) - 0.5f) < 0.01f;
        mir$cellIsEdge = xMid != yMid;
        mir$cellFace = direction;
        return emitter.square(direction, minX, minY, maxX, maxY, offset);
    }

    @Redirect(method = "onBlockOutline", require = 0, at = @At(value = "INVOKE",
            target = "Laztech/modern_industrialization/client/util/RenderHelper;quadWithAlpha("
                    + "Lcom/mojang/blaze3d/vertex/VertexConsumer;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
                    + "Lnet/minecraft/client/renderer/block/model/BakedQuad;FFFFII)V"))
    private static void mir$tintHoveredEdgeCell(VertexConsumer vc, PoseStack.Pose pose, BakedQuad quad,
            float r, float g, float b, float alpha, int light, int overlay) {
        boolean hovered = g > 0.5f;
        boolean bufferTopOrBottom = mir$targetIsBuffer && (mir$cellFace == Direction.UP || mir$cellFace == Direction.DOWN);
        if (mir$targetElectrified && mir$cellIsEdge && hovered && !bufferTopOrBottom) {
            RenderHelper.quadWithAlpha(vc, pose, quad, EDGE_ZONE_R, EDGE_ZONE_G, EDGE_ZONE_B, alpha, light, overlay);
        } else {
            RenderHelper.quadWithAlpha(vc, pose, quad, r, g, b, alpha, light, overlay);
        }
    }
}
