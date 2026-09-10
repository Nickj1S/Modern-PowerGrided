package mirefresh.mir.menu;

import mirefresh.mir.machine.MachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * Menu for any {@link MachineBlockEntity}. Slot layout is furnace-like: input(s) on the left,
 * output(s) on the right, standard player inventory below. Progress / watts / voltage / max-watts
 * ride a 4-int {@link ContainerData}.
 *
 * <p>Server side the ContainerData is the BE's own live view (read on every broadcast). Client
 * side it MUST be a {@link SimpleContainerData} so the synced values are actually stored — the
 * BE's live view returns 0 on the client (its electrical tick never runs there).
 */
public class MachineMenu extends AbstractContainerMenu {

    private final ContainerLevelAccess access;
    private final ContainerData data;
    private final int inputSlots;
    private final int machineSlots;

    private MachineMenu(MenuType<?> type, int id, Inventory playerInv, IItemHandler machineInv,
                        int inputSlots, int outputSlots, ContainerData data, ContainerLevelAccess access) {
        super(type, id);
        this.inputSlots = inputSlots;
        this.machineSlots = inputSlots + outputSlots;
        this.data = data;
        this.access = access;

        for (int i = 0; i < inputSlots; i++) {
            addSlot(new SlotItemHandler(machineInv, i, 56, 35 - (inputSlots - 1) * 9 + i * 18));
        }
        for (int i = 0; i < outputSlots; i++) {
            addSlot(new SlotItemHandler(machineInv, inputSlots + i, 112, 35 - (outputSlots - 1) * 9 + i * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInv, col, 8 + col * 18, 142));

        addDataSlots(data);
    }

    /** Server-side: built from the real block entity. */
    @SuppressWarnings("unchecked")
    public static MachineMenu forServer(int id, Inventory playerInv, MachineBlockEntity be) {
        return new MachineMenu((MenuType<MachineMenu>) be.machineType().menuType().get(), id, playerInv,
                be.inventory(), be.machineType().inputSlots(), be.machineType().outputSlots(),
                be.containerData(), ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()));
    }

    /** Client-side: reads the target pos from the open packet, mirrors layout, stores synced data. */
    @SuppressWarnings("unchecked")
    public static MachineMenu fromNetwork(int id, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        if (playerInv.player.level().getBlockEntity(pos) instanceof MachineBlockEntity be) {
            return new MachineMenu((MenuType<MachineMenu>) be.machineType().menuType().get(), id, playerInv,
                    be.inventory(), be.machineType().inputSlots(), be.machineType().outputSlots(),
                    new SimpleContainerData(4), ContainerLevelAccess.NULL);
        }
        // Desync fallback: no BE at pos. Use the (single, v0.1) machine menu type so we still
        // build a valid menu instead of crashing the client.
        return new MachineMenu(mirefresh.mir.Registration.electricFurnaceMenu(), id, playerInv,
                new ItemStackHandler(2), 1, 1, new SimpleContainerData(4), ContainerLevelAccess.NULL);
    }

    public float progress01() {
        return data.get(0) / 1000f;
    }

    public int watts() {
        return data.get(1);
    }

    public int voltage() {
        return data.get(2);
    }

    public int maxWatts() {
        return Math.max(1, data.get(3));
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) -> level.getBlockEntity(pos) instanceof MachineBlockEntity, true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < machineSlots) {
                if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
            } else {
                if (!moveItemStackTo(stack, 0, inputSlots, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return result;
    }
}
