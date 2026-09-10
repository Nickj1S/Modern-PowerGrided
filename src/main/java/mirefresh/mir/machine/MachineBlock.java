package mirefresh.mir.machine;

import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import org.patryk3211.powergrid.electricity.base.DirectionalElectricBlock;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * Full-cube electric machine block. Two wire terminals ({@code +}/{@code -}) sit on the face
 * pointing away from {@link DirectionalElectricBlock#FACING}. Wire interactions are dispatched to
 * {@link org.patryk3211.powergrid.electricity.base.IElectric#onWire} by PowerGrid's own global
 * handler (see {@code WireItem.useOn}); an empty-hand right-click opens the machine GUI.
 */
public class MachineBlock extends DirectionalElectricBlock implements IBE<MachineBlockEntity> {

    // Defined for a NORTH-facing block; directionalNorthTerminals() rotates them per FACING.
    private static final TerminalBoundingBox[] TERMINALS = {
            new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 3.0, 6.0, 0.0, 6.0, 9.0, 3.0)
                    .withColor(IDecoratedTerminal.RED),
            new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 10.0, 6.0, 0.0, 13.0, 9.0, 3.0)
                    .withColor(IDecoratedTerminal.BLUE),
    };

    private final MachineType machineType;

    public MachineBlock(Properties properties, MachineType machineType) {
        super(properties);
        this.machineType = machineType;
        setTerminalCollection(directionalNorthTerminals(this, TERMINALS, Shapes.block()));
    }

    public MachineType machineType() {
        return machineType;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!player.getItemInHand(hand).isEmpty()) {
            return InteractionResult.PASS; // let wire / other item interactions run
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MenuProvider provider) {
            player.openMenu(provider, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public Class<MachineBlockEntity> getBlockEntityClass() {
        return MachineBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MachineBlockEntity> getBlockEntityType() {
        return machineType.blockEntityType().get();
    }
}
