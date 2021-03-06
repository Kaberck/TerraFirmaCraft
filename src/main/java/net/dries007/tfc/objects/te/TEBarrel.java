/*
 * Work under Copyright. Licensed under the EUPL.
 * See the project README.md and LICENSE.txt for more information.
 */

package net.dries007.tfc.objects.te;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.*;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;

import net.dries007.tfc.ConfigTFC;
import net.dries007.tfc.api.capability.food.CapabilityFood;
import net.dries007.tfc.api.recipes.barrel.BarrelRecipe;
import net.dries007.tfc.objects.fluids.FluidsTFC;
import net.dries007.tfc.objects.fluids.capability.FluidHandlerSided;
import net.dries007.tfc.objects.fluids.capability.FluidTankCallback;
import net.dries007.tfc.objects.fluids.capability.IFluidHandlerSidedCallback;
import net.dries007.tfc.objects.fluids.capability.IFluidTankCallback;
import net.dries007.tfc.objects.fluids.properties.PreservingProperty;
import net.dries007.tfc.objects.inventory.capability.IItemHandlerSidedCallback;
import net.dries007.tfc.objects.inventory.capability.ItemHandlerSidedWrapper;
import net.dries007.tfc.util.FluidTransferHelper;
import net.dries007.tfc.util.calendar.CalendarTFC;
import net.dries007.tfc.util.calendar.ICalendarFormatted;

import static net.dries007.tfc.objects.blocks.wood.BlockBarrel.SEALED;

@ParametersAreNonnullByDefault
public class TEBarrel extends TETickableInventory implements ITickable, IItemHandlerSidedCallback, IFluidHandlerSidedCallback, IFluidTankCallback
{
    public static final int SLOT_FLUID_CONTAINER_IN = 0;
    public static final int SLOT_FLUID_CONTAINER_OUT = 1;
    public static final int SLOT_ITEM = 2;
    public static final int BARREL_MAX_FLUID_TEMPERATURE = 500;

    private final FluidTank tank = new FluidTankCallback(this, 0, ConfigTFC.Devices.BARREL.tank);
    private final Queue<ItemStack> surplus = new LinkedList<>(); // Surplus items from a recipe with output > stackSize
    private boolean sealed;
    private long sealedTick, sealedCalendarTick;
    private BarrelRecipe recipe;
    private int tickCounter;
    private boolean checkInstantRecipe = false;

    public TEBarrel()
    {
        super(3);
    }

    /**
     * Called when this TileEntity was created by placing a sealed Barrel Item.
     * Loads its data from the Item's NBTTagCompound without loading xyz coordinates.
     *
     * @param nbt The NBTTagCompound to load from.
     */
    public void readFromItemTag(NBTTagCompound nbt)
    {
        tank.readFromNBT(nbt.getCompoundTag("tank"));
        if (tank.getFluidAmount() > tank.getCapacity())
        {
            // Fix config changes
            FluidStack fluidStack = tank.getFluid();
            if (fluidStack != null)
            {
                fluidStack.amount = tank.getCapacity();
            }
            tank.setFluid(fluidStack);
        }
        inventory.deserializeNBT(nbt.getCompoundTag("inventory"));
        sealedTick = nbt.getLong("sealedTick");
        sealedCalendarTick = nbt.getLong("sealedCalendarTick");

        surplus.clear();
        if (nbt.hasKey("surplus"))
        {
            NBTTagList surplusItems = nbt.getTagList("surplus", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < surplusItems.tagCount(); i++)
            {
                surplus.add(new ItemStack(surplusItems.getCompoundTagAt(i)));
            }
        }
        sealed = true;
        recipe = BarrelRecipe.get(inventory.getStackInSlot(SLOT_ITEM), tank.getFluid());
        markForSync();
    }

    /**
     * Called once per side when the TileEntity has finished loading.
     * On servers, this is the earliest point in time to safely access the TE's World object.
     */
    @Override
    public void onLoad()
    {
        if (!world.isRemote)
        {
            sealed = world.getBlockState(pos).getValue(SEALED);
            recipe = BarrelRecipe.get(inventory.getStackInSlot(SLOT_ITEM), tank.getFluid());
        }
    }

    @Nullable
    public BarrelRecipe getRecipe()
    {
        return recipe;
    }

    @Nonnull
    public String getSealedDate()
    {
        return ICalendarFormatted.getTimeAndDate(sealedCalendarTick, CalendarTFC.CALENDAR_TIME.getDaysInMonth());
    }

    @Override
    public void setAndUpdateFluidTank(int fluidTankID)
    {
        markForSync();
    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, EnumFacing side)
    {
        return !sealed && (isItemValid(slot, stack) || side == null && slot == SLOT_FLUID_CONTAINER_OUT);
    }

    @Override
    public boolean canExtract(int slot, EnumFacing side)
    {
        return !sealed && (side == null || slot != SLOT_FLUID_CONTAINER_IN);
    }

    @Override
    public boolean canFill(FluidStack resource, EnumFacing side)
    {
        return !sealed && (resource.getFluid() == null || resource.getFluid().getTemperature(resource) < BARREL_MAX_FLUID_TEMPERATURE);
    }

    @Override
    public boolean canDrain(EnumFacing side)
    {
        return !sealed;
    }

    public void onSealed()
    {
        sealedTick = CalendarTFC.PLAYER_TIME.getTicks();
        sealedCalendarTick = CalendarTFC.CALENDAR_TIME.getTicks();
        recipe = BarrelRecipe.get(inventory.getStackInSlot(SLOT_ITEM), tank.getFluid());
        sealed = true;

        // Any food sealed in the barrel gets the property applied, provided there's at least a bucket worth in the vessel
        FluidStack inputFluid = tank.getFluid();
        if (inputFluid != null)
        {
            PreservingProperty property = FluidsTFC.getWrapper(inputFluid.getFluid()).get(PreservingProperty.PRESERVING);
            ItemStack sealedStack = inventory.getStackInSlot(SLOT_ITEM);
            if (property != null && inputFluid.amount >= Fluid.BUCKET_VOLUME && property.test(sealedStack))
            {
                CapabilityFood.applyTrait(sealedStack, property.getTrait());
            }
        }
        markForSync();
    }

    public void onUnseal()
    {
        sealedTick = sealedCalendarTick = 0;
        recipe = null;
        sealed = false;

        // Remove preserving property
        FluidStack inputFluid = tank.getFluid();
        if (inputFluid != null)
        {
            PreservingProperty property = FluidsTFC.getWrapper(inputFluid.getFluid()).get(PreservingProperty.PRESERVING);
            ItemStack sealedStack = inventory.getStackInSlot(SLOT_ITEM);
            if (property != null)
            {
                CapabilityFood.removeTrait(sealedStack, property.getTrait());
            }
        }
        markForSync();
    }

    @Override
    public void update()
    {
        super.update();
        if (!world.isRemote)
        {
            tickCounter++;
            if (tickCounter == 10)
            {
                tickCounter = 0;

                ItemStack fluidContainerIn = inventory.getStackInSlot(SLOT_FLUID_CONTAINER_IN);
                FluidActionResult result = FluidTransferHelper.emptyContainerIntoTank(fluidContainerIn, tank, inventory, SLOT_FLUID_CONTAINER_OUT, ConfigTFC.Devices.BARREL.tank, world, pos);

                if (!result.isSuccess())
                {
                    result = FluidTransferHelper.fillContainerFromTank(fluidContainerIn, tank, inventory, SLOT_FLUID_CONTAINER_OUT, ConfigTFC.Devices.BARREL.tank, world, pos);
                }

                if (result.isSuccess())
                {
                    inventory.setStackInSlot(SLOT_FLUID_CONTAINER_IN, result.getResult());
                }

                Fluid freshWater = FluidRegistry.getFluid("fresh_water");

                if (!sealed && world.isRainingAt(pos.up()) && (tank.getFluid() == null || tank.getFluid().getFluid() == freshWater))
                {
                    tank.fill(new FluidStack(freshWater, 10), true);
                }

                if (inventory.getStackInSlot(SLOT_ITEM) == ItemStack.EMPTY && !surplus.isEmpty())
                {
                    inventory.setStackInSlot(SLOT_ITEM, surplus.poll());
                }
            }

            // Check if recipe is complete (sealed recipes only)
            if (recipe != null && sealed)
            {
                int durationSealed = (int) (CalendarTFC.PLAYER_TIME.getTicks() - sealedTick);
                if (durationSealed > recipe.getDuration())
                {
                    ItemStack inputStack = inventory.getStackInSlot(SLOT_ITEM);
                    FluidStack inputFluid = tank.getFluid();
                    if (recipe.isValidInput(inputFluid, inputStack))
                    {
                        tank.setFluid(recipe.getOutputFluid(inputFluid, inputStack));
                        List<ItemStack> output = recipe.getOutputItem(inputFluid, inputStack);
                        ItemStack first = output.get(0);
                        output.remove(0);
                        inventory.setStackInSlot(SLOT_ITEM, first);
                        surplus.addAll(output);
                        markForSync();
                        onSealed(); //run the sealed check again in case we have a new valid recipe.
                    }
                    else
                    {
                        recipe = null;
                    }
                }
            }

            if (checkInstantRecipe)
            {
                ItemStack inputStack = inventory.getStackInSlot(SLOT_ITEM);
                FluidStack inputFluid = tank.getFluid();
                BarrelRecipe instantRecipe = BarrelRecipe.getInstant(inputStack, inputFluid);
                if (instantRecipe != null && inputFluid != null && instantRecipe.isValidInputInstant(inputStack, inputFluid))
                {
                    tank.setFluid(instantRecipe.getOutputFluid(inputFluid, inputStack));
                    List<ItemStack> output = instantRecipe.getOutputItem(inputFluid, inputStack);
                    ItemStack first = output.get(0);
                    output.remove(0);
                    inventory.setStackInSlot(SLOT_ITEM, first);
                    surplus.addAll(output);
                    instantRecipe.onRecipeComplete(world, pos);
                    markForSync();
                }
                else
                {
                    checkInstantRecipe = false;
                }
            }
        }
    }

    public boolean isSealed()
    {
        return sealed;
    }

    @Override
    public void setAndUpdateSlots(int slot)
    {
        checkInstantRecipe = true;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt)
    {
        super.readFromNBT(nbt);

        tank.readFromNBT(nbt.getCompoundTag("tank"));
        if (tank.getFluidAmount() > tank.getCapacity())
        {
            // Fix config changes
            FluidStack fluidStack = tank.getFluid();
            if (fluidStack != null)
            {
                fluidStack.amount = tank.getCapacity();
            }
            tank.setFluid(fluidStack);
        }
        sealedTick = nbt.getLong("sealedTick");
        sealedCalendarTick = nbt.getLong("sealedCalendarTick");
        sealed = nbt.getBoolean("sealed");

        surplus.clear();
        if (nbt.hasKey("surplus"))
        {
            NBTTagList surplusItems = nbt.getTagList("surplus", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < surplusItems.tagCount(); i++)
            {
                surplus.add(new ItemStack(surplusItems.getCompoundTagAt(i)));
            }
        }

        recipe = BarrelRecipe.get(inventory.getStackInSlot(SLOT_ITEM), tank.getFluid());
    }

    @Override
    @Nonnull
    public NBTTagCompound writeToNBT(NBTTagCompound nbt)
    {
        nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
        nbt.setLong("sealedTick", sealedTick);
        nbt.setLong("sealedCalendarTick", sealedCalendarTick);
        nbt.setBoolean("sealed", sealed);

        if (!surplus.isEmpty())
        {
            NBTTagList surplusList = new NBTTagList();
            for (ItemStack stack : surplus)
            {
                surplusList.appendTag(stack.serializeNBT());
            }
            nbt.setTag("surplus", surplusList);
        }

        return super.writeToNBT(nbt);
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing)
    {
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing)
    {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
        {
            return (T) new ItemHandlerSidedWrapper(this, inventory, facing);
        }

        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
        {
            return (T) new FluidHandlerSided(this, tank, facing);
        }

        return super.getCapability(capability, facing);
    }

    @Override
    public void onBreakBlock(World world, BlockPos pos, IBlockState state)
    {
        if (state.getValue(SEALED))
        {
            ItemStack barrelStack = new ItemStack(state.getBlock());

            if (recipe != null)
            {
                // Drop the sealed barrel with inventory only if it's a valid recipe.
                barrelStack.setTagCompound(getItemTag());
                InventoryHelper.spawnItemStack(world, pos.getX(), pos.getY(), pos.getZ(), barrelStack);
            }
            else
            {
                // Drop the sealed barrel minus inventory if there is no recipe.
                int slotsToDrop = inventory.getSlots();
                for (int i = 0; i < slotsToDrop; i++)
                {
                    InventoryHelper.spawnItemStack(world, pos.getX(), pos.getY(), pos.getZ(), inventory.getStackInSlot(i));
                    inventory.setStackInSlot(i, new ItemStack(Items.AIR, 0));
                }
                if (!surplus.isEmpty())
                {
                    for (ItemStack surplusToDrop : surplus)
                    {
                        InventoryHelper.spawnItemStack(world, pos.getX(), pos.getY(), pos.getZ(), surplusToDrop);
                    }
                    surplus.clear();
                }
                barrelStack.setTagCompound(getItemTag());
                InventoryHelper.spawnItemStack(world, pos.getX(), pos.getY(), pos.getZ(), barrelStack);
            }
        }
        else
        {
            // Drop contents only, actual barrel will be dropped normally
            super.onBreakBlock(world, pos, state);
        }
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack)
    {
        switch (slot)
        {
            case SLOT_FLUID_CONTAINER_IN:
                return stack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null);
            case SLOT_ITEM:
                return true;
            default:
                return false;
        }
    }

    /**
     * Called to get the NBTTagCompound that is put on Barrel Items.
     * This happens when a sealed Barrel was broken.
     *
     * Public access needed from BlockBarrel during getPickBlock
     *
     * @return An NBTTagCompound containing inventory and tank data.
     */
    public NBTTagCompound getItemTag()
    {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
        nbt.setTag("inventory", inventory.serializeNBT());
        nbt.setLong("sealedTick", sealedTick);
        nbt.setLong("sealedCalendarTick", sealedCalendarTick);

        if (!surplus.isEmpty())
        {
            NBTTagList surplusList = new NBTTagList();
            for (ItemStack stack : surplus)
            {
                surplusList.appendTag(stack.serializeNBT());
            }
            nbt.setTag("surplus", surplusList);
        }

        return nbt;
    }
}
