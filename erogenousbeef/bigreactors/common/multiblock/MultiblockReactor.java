package erogenousbeef.bigreactors.common.multiblock;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.oredict.OreDictionary;
import cofh.api.energy.IEnergyHandler;
import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.common.network.Player;
import erogenousbeef.bigreactors.api.IHeatEntity;
import erogenousbeef.bigreactors.api.RadiationData;
import erogenousbeef.bigreactors.common.BRLog;
import erogenousbeef.bigreactors.common.BRRegistry;
import erogenousbeef.bigreactors.common.BigReactors;
import erogenousbeef.bigreactors.common.interfaces.IMultipleFluidHandler;
import erogenousbeef.bigreactors.common.interfaces.IReactorFuelInfo;
import erogenousbeef.bigreactors.common.multiblock.block.BlockReactorPart;
import erogenousbeef.bigreactors.common.multiblock.helpers.CoolantContainer;
import erogenousbeef.bigreactors.common.multiblock.helpers.FuelContainer;
import erogenousbeef.bigreactors.common.multiblock.helpers.RadiationHelper;
import erogenousbeef.bigreactors.common.multiblock.interfaces.ITickableMultiblockPart;
import erogenousbeef.bigreactors.common.multiblock.tileentity.TileEntityReactorAccessPort;
import erogenousbeef.bigreactors.common.multiblock.tileentity.TileEntityReactorControlRod;
import erogenousbeef.bigreactors.common.multiblock.tileentity.TileEntityReactorCoolantPort;
import erogenousbeef.bigreactors.common.multiblock.tileentity.TileEntityReactorFuelRod;
import erogenousbeef.bigreactors.common.multiblock.tileentity.TileEntityReactorPart;
import erogenousbeef.bigreactors.common.multiblock.tileentity.TileEntityReactorPowerTap;
import erogenousbeef.bigreactors.net.PacketWrapper;
import erogenousbeef.bigreactors.net.Packets;
import erogenousbeef.bigreactors.utils.StaticUtils;
import erogenousbeef.core.common.CoordTriplet;
import erogenousbeef.core.multiblock.IMultiblockPart;
import erogenousbeef.core.multiblock.MultiblockControllerBase;
import erogenousbeef.core.multiblock.MultiblockValidationException;
import erogenousbeef.core.multiblock.rectangular.RectangularMultiblockControllerBase;

public class MultiblockReactor extends RectangularMultiblockControllerBase implements IEnergyHandler, IReactorFuelInfo, IMultipleFluidHandler {
	public static final int AmountPerIngot = 1000; // 1 ingot = 1000 mB
	public static final int FuelCapacityPerFuelRod = 4 * AmountPerIngot; // 4 ingots per rod
	
	public static final int FLUID_SUPERHEATED = CoolantContainer.HOT;
	public static final int FLUID_COOLANT = CoolantContainer.COLD;
	
	private static final float passiveCoolingPowerEfficiency = 0.5f; // 50% power penalty, so this comes out as about 1/3 a basic water-cooled reactor
	private static final float passiveCoolingTransferEfficiency = 0.2f; // 20% of available heat transferred per tick when passively cooled
	private static final float reactorHeatLossConductivity = 0.001f; // circa 1RF per tick per external surface block
	
	// Game stuff - stored
	protected boolean active;
	private float reactorHeat;
	private float fuelHeat;
	private WasteEjectionSetting wasteEjection;
	private float energyStored;
	protected FuelContainer fuelContainer;
	protected RadiationHelper radiationHelper;
	protected CoolantContainer coolantContainer;

	// Game stuff - derived at runtime
	protected float fuelToReactorHeatTransferCoefficient;
	protected float reactorToCoolantSystemHeatTransferCoefficient;
	protected float reactorHeatLossCoefficient;
	
	protected Iterator<TileEntityReactorFuelRod> currentFuelRod;
	int reactorVolume;

	// UI stuff
	private float energyGeneratedLastTick;
	private float fuelConsumedLastTick;
	
	public enum WasteEjectionSetting {
		kAutomatic,					// Full auto, always remove waste
		kAutomaticOnlyIfCanReplace, // Remove only if it can be replaced with fuel
		kManual, 					// Manual, only on button press
	}
	
	// Lists of connected parts
	private Set<TileEntityReactorPowerTap> attachedPowerTaps;
	private Set<ITickableMultiblockPart> attachedTickables;

	private Set<TileEntityReactorControlRod> attachedControlRods; 	// Highest internal Y-coordinate in the fuel column
	private Set<TileEntityReactorAccessPort> attachedAccessPorts;
	private Set<TileEntityReactorPart> attachedControllers;
	
	private Set<TileEntityReactorFuelRod> attachedFuelRods;
	private Set<TileEntityReactorCoolantPort> attachedCoolantPorts;

	// Updates
	private Set<EntityPlayer> updatePlayers;
	private int ticksSinceLastUpdate;
	private static final int ticksBetweenUpdates = 3;
	private static final int maxEnergyStored = 10000000;
	
	public MultiblockReactor(World world) {
		super(world);

		// Game stuff
		active = false;
		reactorHeat = 0f;
		fuelHeat = 0f;
		energyStored = 0f;
		wasteEjection = WasteEjectionSetting.kAutomatic;

		// Derived stats
		fuelToReactorHeatTransferCoefficient = 0f;
		reactorToCoolantSystemHeatTransferCoefficient = 0f;
		reactorHeatLossCoefficient = 0f;
		
		// UI and stats
		energyGeneratedLastTick = 0f;
		fuelConsumedLastTick = 0f;
		
		
		attachedPowerTaps = new HashSet<TileEntityReactorPowerTap>();
		attachedTickables = new HashSet<ITickableMultiblockPart>();
		attachedControlRods = new HashSet<TileEntityReactorControlRod>();
		attachedAccessPorts = new HashSet<TileEntityReactorAccessPort>();
		attachedControllers = new HashSet<TileEntityReactorPart>();
		attachedFuelRods = new HashSet<TileEntityReactorFuelRod>();
		attachedCoolantPorts = new HashSet<TileEntityReactorCoolantPort>();
		
		currentFuelRod = null;

		updatePlayers = new HashSet<EntityPlayer>();
		
		ticksSinceLastUpdate = 0;
		fuelContainer = new FuelContainer();
		radiationHelper = new RadiationHelper();
		coolantContainer = new CoolantContainer();
		
		reactorVolume = 0;
	}
	
	public void beginUpdatingPlayer(EntityPlayer playerToUpdate) {
		updatePlayers.add(playerToUpdate);
		sendIndividualUpdate(playerToUpdate);
	}
	
	public void stopUpdatingPlayer(EntityPlayer playerToRemove) {
		updatePlayers.remove(playerToRemove);
	}
	
	@Override
	protected void onBlockAdded(IMultiblockPart part) {
		if(part instanceof TileEntityReactorAccessPort) {
			attachedAccessPorts.add((TileEntityReactorAccessPort)part);
		}
		
		if(part instanceof TileEntityReactorControlRod) {
			TileEntityReactorControlRod controlRod = (TileEntityReactorControlRod)part; 
			attachedControlRods.add(controlRod);
			
			// TODO: Remove once 0.2 backwards compatiblity is no longer needed
			if(controlRod.getCachedFuel() != null) {
				fuelContainer.addFuel(controlRod.getCachedFuel());
				
				// Make sure we re-save the control rod, so we don't repeatedly load up with fuel
				worldObj.markTileEntityChunkModified(controlRod.xCoord, controlRod.yCoord, controlRod.zCoord, controlRod);
			}
		}

		if(part instanceof TileEntityReactorPowerTap) {
			attachedPowerTaps.add((TileEntityReactorPowerTap)part);
		}

		if(part instanceof TileEntityReactorPart) {
			TileEntityReactorPart reactorPart = (TileEntityReactorPart)part;
			if(BlockReactorPart.isController(reactorPart.getBlockMetadata())) {
				attachedControllers.add(reactorPart);
			}
		}

		if(part instanceof ITickableMultiblockPart) {
			attachedTickables.add((ITickableMultiblockPart)part);
		}
		
		if(part instanceof TileEntityReactorFuelRod) {
			TileEntityReactorFuelRod fuelRod = (TileEntityReactorFuelRod)part;
			attachedFuelRods.add(fuelRod);

			// Reset iterator
			currentFuelRod = attachedFuelRods.iterator();

			if(worldObj.isRemote) {
				worldObj.markBlockForRenderUpdate(fuelRod.xCoord, fuelRod.yCoord, fuelRod.zCoord);
			}
		}
		
		if(part instanceof TileEntityReactorCoolantPort) {
			attachedCoolantPorts.add((TileEntityReactorCoolantPort) part);
		}
	}
	
	@Override
	protected void onBlockRemoved(IMultiblockPart part) {
		if(part instanceof TileEntityReactorAccessPort) {
			attachedAccessPorts.remove((TileEntityReactorAccessPort)part);
		}

		if(part instanceof TileEntityReactorControlRod) {
			attachedControlRods.remove((TileEntityReactorControlRod)part);
		}

		if(part instanceof TileEntityReactorPowerTap) {
			attachedPowerTaps.remove((TileEntityReactorPowerTap)part);
		}

		if(part instanceof TileEntityReactorPart) {
			TileEntityReactorPart reactorPart = (TileEntityReactorPart)part;
			if(BlockReactorPart.isController(reactorPart.getBlockMetadata())) {
				attachedControllers.remove(reactorPart);
			}
		}

		if(part instanceof ITickableMultiblockPart) {
			attachedTickables.remove((ITickableMultiblockPart)part);
		}
		
		if(part instanceof TileEntityReactorFuelRod) {
			attachedFuelRods.remove(part);
			currentFuelRod = attachedFuelRods.iterator();
		}
		
		if(part instanceof TileEntityReactorCoolantPort) {
			attachedCoolantPorts.remove((TileEntityReactorCoolantPort)part);
		}
	}
	
	@Override
	protected void isMachineWhole() throws MultiblockValidationException {
		// Ensure that there is at least one controller and control rod attached.
		if(attachedControlRods.size() < 1) {
			throw new MultiblockValidationException("Not enough control rods. Reactors require at least 1.");
		}
		
		if(attachedControllers.size() < 1) {
			throw new MultiblockValidationException("Not enough controllers. Reactors require at least 1.");
		}
		
		super.isMachineWhole();
	}

	@Override
	public void updateClient() {}
	
	// Update loop. Only called when the machine is assembled.
	@Override
	public boolean updateServer() {
		if(Float.isNaN(this.getReactorHeat())) {
			this.setReactorHeat(0.0f);
		}
		
		float oldHeat = this.getReactorHeat();
		float oldEnergy = this.getEnergyStored();
		energyGeneratedLastTick = 0f;
		fuelConsumedLastTick = 0f;

		float newHeat = 0f;
		
		if(isActive()) {
			// Select a control rod to radiate from. Reset the iterator and select a new Y-level if needed.
			if(!currentFuelRod.hasNext()) {
				currentFuelRod = attachedFuelRods.iterator();
			}

			// Radiate from that control rod
			TileEntityReactorFuelRod source  = currentFuelRod.next();
			TileEntityReactorControlRod sourceControlRod = (TileEntityReactorControlRod)worldObj.getBlockTileEntity(source.xCoord, getMaximumCoord().y, source.zCoord);
			RadiationData radData = radiationHelper.radiate(worldObj, fuelContainer, source, sourceControlRod, getFuelHeat(), getReactorHeat(), attachedControlRods.size());

			// Assimilate results of radiation
			if(radData != null) {
				addFuelHeat(radData.getFuelHeatChange(attachedFuelRods.size()));
				addReactorHeat(radData.getEnvironmentHeatChange(getReactorVolume()));
				fuelConsumedLastTick += radData.fuelUsage;
			}
		}

		// Allow radiation to decay even when reactor is off.
		radiationHelper.tick(isActive());

		// If we can, poop out waste and inject new fuel.
		refuelAndEjectWaste();

		// Heat Transfer: Fuel Pool <> Reactor Environment
		float tempDiff = fuelHeat - reactorHeat;
		if(tempDiff > 0.01f) {
			float rfTransferred = tempDiff * fuelToReactorHeatTransferCoefficient;
			float fuelRf = StaticUtils.Energy.getRFFromVolumeAndTemp(attachedFuelRods.size(), fuelHeat);
			
			fuelRf -= rfTransferred;
			setFuelHeat(StaticUtils.Energy.getTempFromVolumeAndRF(attachedFuelRods.size(), fuelRf));

			// Now see how much the reactor's temp has increased
			float reactorRf = StaticUtils.Energy.getRFFromVolumeAndTemp(getReactorVolume(), getReactorHeat());
			reactorRf += rfTransferred;
			setReactorHeat(StaticUtils.Energy.getTempFromVolumeAndRF(getReactorVolume(), reactorRf));
		}

		// If we have a temperature differential between environment and coolant system, move heat between them.
		tempDiff = getReactorHeat() - getCoolantTemperature();
		if(tempDiff > 0.01f) {
			float rfTransferred = tempDiff * reactorToCoolantSystemHeatTransferCoefficient;
			float reactorRf = StaticUtils.Energy.getRFFromVolumeAndTemp(getReactorVolume(), getReactorHeat());

			if(isPassivelyCooled()) {
				rfTransferred *= passiveCoolingTransferEfficiency;
				generateEnergy(rfTransferred * passiveCoolingPowerEfficiency);
			}
			else {
				rfTransferred -= coolantContainer.onAbsorbHeat(rfTransferred);
				energyGeneratedLastTick = coolantContainer.getFluidVaporizedLastTick(); // Piggyback so we don't have useless stuff in the update packet
			}

			reactorRf -= rfTransferred;
			setReactorHeat(StaticUtils.Energy.getTempFromVolumeAndRF(getReactorVolume(), reactorRf));
		}

		// Do passive heat loss - this is always versus external environment
		tempDiff = getReactorHeat() - getPassiveCoolantTemperature();
		if(tempDiff > 0.000001f) {
			float rfLost = Math.max(1f, tempDiff * reactorHeatLossCoefficient); // Lose at least 1RF/t
			float reactorNewRf = Math.max(0f, StaticUtils.Energy.getRFFromVolumeAndTemp(getReactorVolume(), getReactorHeat()) - rfLost);
			setReactorHeat(StaticUtils.Energy.getTempFromVolumeAndRF(getReactorVolume(), reactorNewRf));
		}
		
		// Prevent cryogenics
		if(reactorHeat < 0f) { setReactorHeat(0f); }
		if(fuelHeat < 0f) { setFuelHeat(0f); }
		
		// Distribute available power
		int energyAvailable = (int)getEnergyStored();
		int energyRemaining = energyAvailable;
		if(attachedPowerTaps.size() > 0 && energyRemaining > 0) {
			// First, try to distribute fairly
			int splitEnergy = energyRemaining / attachedPowerTaps.size();
			for(TileEntityReactorPowerTap powerTap : attachedPowerTaps) {
				if(energyRemaining <= 0) { break; }
				if(powerTap == null || !powerTap.isConnected()) { continue; }

				energyRemaining -= splitEnergy - powerTap.onProvidePower(splitEnergy);
			}

			// Next, just hose out whatever we can, if we have any left
			if(energyRemaining > 0) {
				for(TileEntityReactorPowerTap powerTap : attachedPowerTaps) {
					if(energyRemaining <= 0) { break; }
					if(powerTap == null || !powerTap.isConnected()) { continue; }

					energyRemaining = powerTap.onProvidePower(energyRemaining);
				}
			}
		}
		
		if(energyAvailable != energyRemaining) {
			reduceStoredEnergy((energyAvailable - energyRemaining));
		}

		// Send updates periodically
		ticksSinceLastUpdate++;
		if(ticksSinceLastUpdate >= ticksBetweenUpdates) {
			ticksSinceLastUpdate = 0;
			sendTickUpdate();
		}
		
		// TODO: Overload/overheat

		// Update any connected tickables
		for(ITickableMultiblockPart tickable : attachedTickables) {
			if(tickable == null) { continue; }
			tickable.onMultiblockServerTick();
		}

		if(fuelContainer.shouldSendFuelingUpdate() || coolantContainer.shouldSendFuelingUpdate()) {
			markReferenceCoordForUpdate();
		}
		
		return (oldHeat != this.getReactorHeat() || oldEnergy != this.getEnergyStored());
	}
	
	public void setStoredEnergy(float oldEnergy) {
		energyStored = oldEnergy;
		if(energyStored < 0.0 || Float.isNaN(energyStored)) {
			energyStored = 0.0f;
		}
		else if(energyStored > maxEnergyStored) {
			energyStored = maxEnergyStored;
		}
	}
	
	/**
	 * Generate energy, internally. Will be multiplied by the BR Setting powerProductionMultiplier
	 * @param newEnergy Base, unmultiplied energy to generate
	 */
	protected void generateEnergy(float newEnergy) {
		this.energyGeneratedLastTick += newEnergy * BigReactors.powerProductionMultiplier;
		this.addStoredEnergy(newEnergy * BigReactors.powerProductionMultiplier);
	}

	/**
	 * Add some energy to the internal storage buffer.
	 * Will not increase the buffer above the maximum or reduce it below 0.
	 * @param newEnergy
	 */
	protected void addStoredEnergy(float newEnergy) {
		if(Float.isNaN(newEnergy)) { return; }

		energyStored += newEnergy;
		if(energyStored > maxEnergyStored) {
			energyStored = maxEnergyStored;
		}
		if(-0.00001f < energyStored && energyStored < 0.00001f) {
			// Clamp to zero
			energyStored = 0f;
		}
	}

	/**
	 * Remove some energy from the internal storage buffer.
	 * Will not reduce the buffer below 0.
	 * @param energy Amount by which the buffer should be reduced.
	 */
	protected void reduceStoredEnergy(float energy) {
		this.addStoredEnergy(-1f * energy);
	}
	
	public boolean isActive() {
		return this.active;
	}

	public void setActive(boolean act) {
		if(act == this.active) { return; }
		this.active = act;
		
		for(IMultiblockPart part : connectedParts) {
			if(this.active) { part.onMachineActivated(); }
			else { part.onMachineDeactivated(); }
		}
	}

	protected void addReactorHeat(float newCasingHeat) {
		if(Float.isNaN(newCasingHeat)) {
			return;
		}

		reactorHeat += newCasingHeat;
		// Clamp to zero to prevent floating point issues
		if(-0.00001f < reactorHeat && reactorHeat < 0.00001f) { reactorHeat = 0.0f; }
	}
	
	public float getReactorHeat() {
		return reactorHeat;
	}
	
	public void setReactorHeat(float newHeat) {
		if(Float.isNaN(newHeat)) {
			reactorHeat = 0.0f;
		}
		else {
			reactorHeat = newHeat;
		}
	}

	protected void addFuelHeat(float additionalHeat) {
		if(Float.isNaN(additionalHeat)) { return; }
		
		fuelHeat += additionalHeat;
		if(-0.00001f < fuelHeat & fuelHeat < 0.00001f) { fuelHeat = 0f; }
	}
	
	public float getFuelHeat() { return fuelHeat; }
	
	public void setFuelHeat(float newFuelHeat) {
		if(Float.isNaN(newFuelHeat)) { fuelHeat = 0f; }
		else { fuelHeat = newFuelHeat; }
	}
	
	public int getFuelRodCount() {
		return attachedControlRods.size();
	}

	// Static validation helpers
	// Water, air, and metal blocks
	@Override
	protected void isBlockGoodForInterior(World world, int x, int y, int z) throws MultiblockValidationException {
		if(world.isAirBlock(x, y, z)) { return; } // Air is OK

		Material material = world.getBlockMaterial(x, y, z);
		if(material == net.minecraft.block.material.MaterialLiquid.water) {
			return;
		}
		
		int blockId = world.getBlockId(x, y, z);
		if(blockId == Block.blockIron.blockID || blockId == Block.blockGold.blockID || blockId == Block.blockDiamond.blockID || blockId == Block.blockEmerald.blockID) {
			return;
		}
		
		// Permit registered moderator blocks
		int metadata = world.getBlockMetadata(x, y, z);
		int oreId = OreDictionary.getOreID(new ItemStack(blockId, 1, metadata));

		if(oreId >= 0 && BRRegistry.getRadiationModeratorBlockData(OreDictionary.getOreName(oreId)) != null) {
			return;
		}
		
		// Permit TE fluids
		if(blockId > 0 && blockId < Block.blocksList.length) {
			Block blockClass = Block.blocksList[blockId];
			if(blockClass instanceof IFluidBlock) {
				Fluid fluid = ((IFluidBlock)blockClass).getFluid();
				String fluidName = fluid.getName();
				if(BRRegistry.getRadiationModeratorFluidData(fluidName) != null) { return; }

				throw new MultiblockValidationException(String.format("%d, %d, %d - The fluid %s is not valid for the reactor's interior", x, y, z, fluidName));
			}
			else {
				throw new MultiblockValidationException(String.format("%d, %d, %d - %s is not valid for the reactor's interior", x, y, z, blockClass.getLocalizedName()));
			}
		}
		else {
			throw new MultiblockValidationException(String.format("%d, %d, %d - Unrecognized block with ID %d, not valid for the reactor's interior", x, y, z, blockId));
		}
	}
	
	@Override
	public void writeToNBT(NBTTagCompound data) {
		data.setBoolean("reactorActive", this.active);
		data.setFloat("heat", this.reactorHeat);
		data.setFloat("fuelHeat", fuelHeat);
		data.setFloat("storedEnergy", this.energyStored);
		data.setInteger("wasteEjection", this.wasteEjection.ordinal());
		data.setCompoundTag("fuelContainer", fuelContainer.writeToNBT(new NBTTagCompound()));
		data.setCompoundTag("radiation", radiationHelper.writeToNBT(new NBTTagCompound()));
		data.setCompoundTag("coolantContainer", coolantContainer.writeToNBT(new NBTTagCompound()));
	}

	@Override
	public void readFromNBT(NBTTagCompound data) {
		if(data.hasKey("reactorActive")) {
			setActive(data.getBoolean("reactorActive"));
		}
		
		if(data.hasKey("heat")) {
			setReactorHeat(Math.max(getReactorHeat(), data.getFloat("heat")));
		}
		
		if(data.hasKey("storedEnergy")) {
			setStoredEnergy(Math.max(getEnergyStored(), data.getFloat("storedEnergy")));
		}
		
		if(data.hasKey("wasteEjection")) {
			this.wasteEjection = WasteEjectionSetting.values()[data.getInteger("wasteEjection")];
		}
		
		if(data.hasKey("fuelHeat")) {
			setFuelHeat(data.getFloat("fuelHeat"));
		}
		
		if(data.hasKey("fuelContainer")) {
			fuelContainer.readFromNBT(data.getCompoundTag("fuelContainer"));
		}
		
		if(data.hasKey("radiation")) {
			radiationHelper.readFromNBT(data.getCompoundTag("radiation"));
		}
		
		if(data.hasKey("coolantContainer")) {
			coolantContainer.readFromNBT(data.getCompoundTag("coolantContainer"));
		}
	}

	@Override
	protected int getMinimumNumberOfBlocksForAssembledMachine() {
		// Hollow cube.
		return 26;
	}

	@Override
	public void formatDescriptionPacket(NBTTagCompound data) {
		data.setInteger("wasteEjection", this.wasteEjection.ordinal());
		data.setFloat("energy", this.energyStored);
		data.setFloat("heat", this.reactorHeat);
		data.setBoolean("isActive", this.isActive());
		data.setFloat("fuelHeat", fuelHeat);
		data.setCompoundTag("fuelContainer", fuelContainer.writeToNBT(new NBTTagCompound()));
		data.setCompoundTag("radiation", radiationHelper.writeToNBT(new NBTTagCompound()));
		data.setCompoundTag("coolantContainer", coolantContainer.writeToNBT(new NBTTagCompound()));
	}

	@Override
	public void decodeDescriptionPacket(NBTTagCompound data) {
		if(data.hasKey("wasteEjection")) {
			this.wasteEjection = WasteEjectionSetting.values()[data.getInteger("wasteEjection")];
		}
		
		if(data.hasKey("isActive")) {
			this.setActive(data.getBoolean("isActive"));
		}
		
		if(data.hasKey("energy")) {
			this.energyStored = data.getFloat("energy");
		}
		
		if(data.hasKey("heat")) {
			setReactorHeat(data.getFloat("heat"));
		}
		
		if(data.hasKey("fuelHeat")) {
			setFuelHeat(data.getFloat("fuelHeat"));
		}
		
		if(data.hasKey("fuelContainer")) {
			fuelContainer.readFromNBT(data.getCompoundTag("fuelContainer"));
		}

		if(data.hasKey("radiation")) {
			radiationHelper.readFromNBT(data.getCompoundTag("radiation"));
		}
		
		if(data.hasKey("coolantContainer")) {
			coolantContainer.readFromNBT(data.getCompoundTag("coolantContainer"));
		}
		
		onFuelStatusChanged();
	}

	protected Packet getUpdatePacket() {
		Fluid fuelType, wasteType, coolantType, vaporType;
		fuelType = fuelContainer.getFuelType();
		wasteType = fuelContainer.getWasteType();
		coolantType = coolantContainer.getCoolantType();
		vaporType = coolantContainer.getVaporType();
		
		int fuelTypeID, wasteTypeID, coolantTypeID, vaporTypeID;
		fuelTypeID = fuelType == null ? -1 : fuelType.getID();
		wasteTypeID = wasteType == null ? -1 : wasteType.getID();
		coolantTypeID = coolantType == null ? -1 : coolantType.getID();
		vaporTypeID = vaporType == null ? -1 : vaporType.getID();
		
		CoordTriplet coord = getReferenceCoord();

		return PacketWrapper.createPacket(BigReactors.CHANNEL,
				 Packets.ReactorControllerFullUpdate,
				 new Object[] { coord.x,
								coord.y,
								coord.z,
								this.active,
								this.reactorHeat,
								energyStored,
								this.energyGeneratedLastTick,
								this.fuelConsumedLastTick,
								this.fuelHeat,
								fuelTypeID,
								fuelContainer.getFuelAmount(),
								wasteTypeID,
								fuelContainer.getWasteAmount(),
								radiationHelper.getFertility(),
								coolantTypeID,
								coolantContainer.getCoolantAmount(),
								vaporTypeID,
								coolantContainer.getVaporAmount()});
	}
	
	public void receiveReactorUpdate(DataInputStream data) throws IOException {
		boolean active = data.readBoolean();
		float heat = data.readFloat();
		float storedEnergy = data.readFloat();
		float energyGeneratedLastTick = data.readFloat();
		float fuelConsumedLastTick = data.readFloat();
		float fuelHeat = data.readFloat();
		int fuelTypeID = data.readInt();
		int fuelAmt = data.readInt();
		int wasteTypeID = data.readInt();
		int wasteAmt = data.readInt();
		float fertility = data.readFloat();
		int coolantTypeID = data.readInt();
		int coolantAmt = data.readInt();
		int vaporTypeID = data.readInt();
		int vaporAmt = data.readInt();

		setActive(active);
		setReactorHeat(heat);
		setStoredEnergy(storedEnergy);
		setEnergyGeneratedLastTick(energyGeneratedLastTick);
		setFuelConsumedLastTick(fuelConsumedLastTick);
		setFuelHeat(fuelHeat);
		
		radiationHelper.setFertility(fertility);
		
		if(fuelTypeID == -1) {
			fuelContainer.emptyFuel();
		}
		else {
			fuelContainer.setFuel(new FluidStack(FluidRegistry.getFluid(fuelTypeID), fuelAmt));
		}
		
		if(wasteTypeID == -1) {
			fuelContainer.emptyWaste();
		}
		else {
			fuelContainer.setWaste(new FluidStack(FluidRegistry.getFluid(wasteTypeID), wasteAmt));
		}
		
		if(coolantTypeID == -1) {
			coolantContainer.emptyCoolant();
		}
		else {
			coolantContainer.setCoolant(new FluidStack(FluidRegistry.getFluid(coolantTypeID), coolantAmt));
		}
		
		if(vaporTypeID == -1) {
			coolantContainer.emptyVapor();
		}
		else {
			coolantContainer.setVapor(new FluidStack(FluidRegistry.getFluid(vaporTypeID), vaporAmt));
		}
	}
	
	/**
	 * Sends a full state update to a player.
	 */
	protected void sendIndividualUpdate(EntityPlayer player) {
		if(this.worldObj.isRemote) { return; }

		PacketDispatcher.sendPacketToPlayer(getUpdatePacket(), (Player)player);
	}
	
	/**
	 * Send an update to any clients with GUIs open
	 * @param energyGenerated 
	 */
	protected void sendTickUpdate() {
		if(this.worldObj.isRemote) { return; }
		if(this.updatePlayers.size() <= 0) { return; }
		
		Packet data = getUpdatePacket();

		for(EntityPlayer player : updatePlayers) {
			PacketDispatcher.sendPacketToPlayer(data, (Player)player);
		}
	}
	
	/**
	 * Attempt to distribute a stack of ingots to a given access port, sensitive to the amount and type of ingots already in it.
	 * @param port The port to which we're distributing ingots.
	 * @param itemsToDistribute The stack of ingots to distribute. Will be modified during the operation and may be returned with stack size 0.
	 * @param distributeToInputs Should we try to send ingots to input ports?
	 * @return The number of waste items distributed, i.e. the differential in stack size for wasteToDistribute.
	 */
	private int tryDistributeItems(TileEntityReactorAccessPort port, ItemStack itemsToDistribute, boolean distributeToInputs) {
		ItemStack existingStack = port.getStackInSlot(TileEntityReactorAccessPort.SLOT_OUTLET);
		CoordTriplet coord = port.getWorldLocation();
		int initialWasteAmount = itemsToDistribute.stackSize;

		int metadata = worldObj.getBlockMetadata(coord.x, coord.y, coord.z);

		if(metadata == BlockReactorPart.ACCESSPORT_OUTLET || (distributeToInputs || attachedAccessPorts.size() < 2)) {
			// Dump waste preferentially to outlets, unless we only have one access port
			if(existingStack == null) {
				if(itemsToDistribute.stackSize > port.getInventoryStackLimit()) {
					ItemStack newStack = itemsToDistribute.splitStack(port.getInventoryStackLimit());
					port.setInventorySlotContents(TileEntityReactorAccessPort.SLOT_OUTLET, newStack);
				}
				else {
					port.setInventorySlotContents(TileEntityReactorAccessPort.SLOT_OUTLET, itemsToDistribute.copy());
					itemsToDistribute.stackSize = 0;
				}
			}
			else if(existingStack.isItemEqual(itemsToDistribute)) {
				if(existingStack.stackSize + itemsToDistribute.stackSize <= existingStack.getMaxStackSize()) {
					existingStack.stackSize += itemsToDistribute.stackSize;
					itemsToDistribute.stackSize = 0;
				}
				else {
					int amt = existingStack.getMaxStackSize() - existingStack.stackSize;
					itemsToDistribute.stackSize -= existingStack.getMaxStackSize() - existingStack.stackSize;
					existingStack.stackSize += amt;
				}
			}

			port.onItemsReceived();
		}
		
		return initialWasteAmount - itemsToDistribute.stackSize;
	}

	@Override
	protected void onAssimilated(MultiblockControllerBase otherMachine) {
		this.attachedPowerTaps.clear();
		this.attachedTickables.clear();
		this.attachedAccessPorts.clear();
		this.attachedControllers.clear();
		this.attachedControlRods.clear();
		currentFuelRod = null;
	}
	
	@Override
	protected void onAssimilate(MultiblockControllerBase otherMachine) {
		if(!(otherMachine instanceof MultiblockReactor)) {
			BRLog.warning("[%s] Reactor @ %s is attempting to assimilate a non-Reactor machine! That machine's data will be lost!", worldObj.isRemote?"CLIENT":"SERVER", getReferenceCoord());
			return;
		}
		
		MultiblockReactor otherReactor = (MultiblockReactor)otherMachine;

		if(otherReactor.reactorHeat > this.reactorHeat) { setReactorHeat(otherReactor.reactorHeat); }
		if(otherReactor.fuelHeat > this.fuelHeat) { setFuelHeat(otherReactor.fuelHeat); }

		if(otherReactor.getEnergyStored() > this.getEnergyStored()) { this.setStoredEnergy(otherReactor.getEnergyStored()); }

		fuelContainer.merge(otherReactor.fuelContainer);
		radiationHelper.merge(otherReactor.radiationHelper);
		coolantContainer.merge(otherReactor.coolantContainer);
	}
	
	@Override
	public void onAttachedPartWithMultiblockData(IMultiblockPart part, NBTTagCompound data) {
		this.readFromNBT(data);
	}
	
	public float getEnergyStored() {
		return energyStored;
	}

	/**
	 * Directly set the waste ejection setting. Will dispatch network updates
	 * from server to interested clients.
	 * @param newSetting The new waste ejection setting.
	 */
	public void setWasteEjection(WasteEjectionSetting newSetting) {
		if(this.wasteEjection != newSetting) {
			this.wasteEjection = newSetting;
			
			if(!this.worldObj.isRemote) {
				if(this.updatePlayers.size() > 0) {
					CoordTriplet coord = getReferenceCoord();
					Packet updatePacket = PacketWrapper.createPacket(BigReactors.CHANNEL,
							 Packets.ReactorWasteEjectionSettingUpdate,
							 new Object[] { coord.x,
											coord.y,
											coord.z,
											this.wasteEjection.ordinal() });
					
					for(EntityPlayer player : updatePlayers) {
						PacketDispatcher.sendPacketToPlayer(updatePacket, (Player)player);
					}
				}
			}
		}
	}
	
	public WasteEjectionSetting getWasteEjection() {
		return this.wasteEjection;
	}
	
	/**
	 * Eject waste from the reactor into the access ports.
	 * @param dumpAll If true, any remaining waste will be emptied out of the fuel container after dumping.
	 */
	public void ejectWaste(boolean dumpAll) {
		int wasteAmt = fuelContainer.getWasteAmount();
		int freeFuelSpace = fuelContainer.getCapacity() - fuelContainer.getTotalAmount();
		
		tryEjectWaste(freeFuelSpace, wasteAmt);
		
		if(dumpAll) {
			fuelContainer.setWaste(null);
			markReferenceCoordDirty();
		}
		
		markReferenceCoordForUpdate();
	}

	protected void refuelAndEjectWaste() {
		int freeFuelSpace = fuelContainer.getCapacity() - fuelContainer.getTotalAmount();
		int wasteIngotsToEject = fuelContainer.getWasteAmount() / AmountPerIngot;

		// Discover how much waste we actually should eject depending on the user's preferences
		if(wasteEjection == WasteEjectionSetting.kManual) {
			// Manual means we wait for the user to hit a button
			wasteIngotsToEject = 0;
		}
		else if(wasteEjection == WasteEjectionSetting.kAutomaticOnlyIfCanReplace) {
			// Find out how many ingots we can replace
			int fuelIngotsAvailable = 0;
			for(TileEntityReactorAccessPort port : attachedAccessPorts) {
				if(port == null || !port.isConnected()) { continue; }

				ItemStack fuelStack = port.getStackInSlot(TileEntityReactorAccessPort.SLOT_INLET);
				if(fuelStack != null) {
					fuelIngotsAvailable += fuelStack.stackSize;
				}
			}
			
			// Cap amount of waste we'll eject to amount of fuel available
			wasteIngotsToEject = Math.min(wasteIngotsToEject, fuelIngotsAvailable);
		}
		
		freeFuelSpace += wasteIngotsToEject * AmountPerIngot;
		
		// Unable to refuel and unable to eject waste
		if(freeFuelSpace < AmountPerIngot && wasteIngotsToEject < 1) {
			return;
		}
		
		tryEjectWaste(freeFuelSpace, wasteIngotsToEject * AmountPerIngot);
	}
	
	/**
	 * Honestly attempt to eject waste and inject fuel, up to a certain amount.
	 * @param fuelAmt Amount of fuel to inject.
	 * @param wasteAmt Amount of waste to eject.
	 */
	protected void tryEjectWaste(int fuelAmt, int wasteAmt) {
		if(fuelAmt < AmountPerIngot && wasteAmt < AmountPerIngot) { return; }

		ItemStack wasteToDistribute = null;
		if(wasteAmt >= AmountPerIngot) {
			// TODO: Make this query the existing fuel type for the right type of waste to create
			wasteToDistribute = OreDictionary.getOres("ingotCyanite").get(0).copy();
			wasteToDistribute.stackSize = wasteAmt/AmountPerIngot;
		}

		int fuelIngotsToConsume = fuelAmt / AmountPerIngot;
		int fuelIngotsConsumed = 0;

		int wasteIngotsDistributed = 0;
		
		// Distribute waste, slurp in ingots.
		for(TileEntityReactorAccessPort port : attachedAccessPorts) {
			if(fuelIngotsToConsume <= 0 && (wasteToDistribute == null || wasteToDistribute.stackSize <= 0)) {
				break;
			}
			
			if(port == null || !port.isConnected()) { continue; }

			ItemStack fuelStack = port.getStackInSlot(TileEntityReactorAccessPort.SLOT_INLET);
			
			if(fuelStack != null) {
				if(fuelStack.stackSize >= fuelIngotsToConsume) {
					fuelStack = StaticUtils.Inventory.consumeItem(fuelStack, fuelIngotsToConsume);
					fuelIngotsConsumed = fuelIngotsToConsume;
					fuelIngotsToConsume = 0;
				}
				else {
					fuelIngotsConsumed += fuelStack.stackSize;
					fuelIngotsToConsume -= fuelStack.stackSize;
					fuelStack = StaticUtils.Inventory.consumeItem(fuelStack, fuelStack.stackSize);
				}
				port.setInventorySlotContents(TileEntityReactorAccessPort.SLOT_INLET, fuelStack);
			}

			if(wasteToDistribute != null && wasteToDistribute.stackSize > 0) {
				wasteIngotsDistributed += tryDistributeItems(port, wasteToDistribute, false);
			}
		}
		
		// If we have waste leftover and we have multiple ports, distribute again so we hit the input ports.
		if(wasteToDistribute != null && wasteToDistribute.stackSize > 0 && attachedAccessPorts.size() > 1) {
			for(TileEntityReactorAccessPort port : attachedAccessPorts) {
				if(wasteToDistribute == null || wasteToDistribute.stackSize <= 0) {
					break;
				}

				if(port == null || !port.isConnected()) { continue; }

				wasteIngotsDistributed += tryDistributeItems(port, wasteToDistribute, true);
			}
		}
		
		// Okay... let's modify the fuel container now
		if(fuelIngotsConsumed > 0) {
			// TODO: Discover fuel type
			fuelContainer.addFuel(new FluidStack(BigReactors.fluidYellorium, fuelIngotsConsumed * AmountPerIngot));
		}
		
		if(wasteIngotsDistributed > 0) {
			fuelContainer.drainWaste(wasteIngotsDistributed * AmountPerIngot);
		}
	}
	
	/**
	 * Eject fuel contained in the reactor.
	 * @param dumpAll If true, any remaining fuel will simply be lost.
	 */
	public void ejectFuel(boolean dumpAll) {
		int ingots = fuelContainer.getFuelAmount() / AmountPerIngot;
		if(ingots <= 0) {
			return;
		}
		
		// TODO: Query the fluid type and use that
		ItemStack ingotsToDistribute = OreDictionary.getOres("ingotYellorium").get(0).copy();
		ingotsToDistribute.stackSize = ingots;
		
		int ingotsDistributed = 0;
		for(TileEntityReactorAccessPort port : attachedAccessPorts) {
			if(ingotsToDistribute == null || ingotsToDistribute.stackSize <= 0) { break; }
			if(port == null || !port.isConnected()) { continue; }
			
			ingotsDistributed += tryDistributeItems(port, ingotsToDistribute, true);
		}
		
		if(ingotsDistributed > 0 || dumpAll) {
			if(dumpAll) {
				fuelContainer.setFuel(null);
			}
			else {
				fuelContainer.drainFuel(ingotsDistributed * AmountPerIngot);
			}

			markReferenceCoordForUpdate();
			markReferenceCoordDirty();
		}
	}

	@Override
	protected void onMachineAssembled() {
		recalculateDerivedValues();
	}

	@Override
	protected void onMachineRestored() {
		recalculateDerivedValues();
	}

	@Override
	protected void onMachinePaused() {
	}

	@Override
	protected void onMachineDisassembled() {
		this.active = false;
	}

	private void recalculateDerivedValues() {
		// Recalculate size of fuel/waste tank via fuel rods
		CoordTriplet minCoord, maxCoord;
		minCoord = getMinimumCoord();
		maxCoord = getMaximumCoord();
		
		fuelContainer.setCapacity(attachedFuelRods.size() * FuelCapacityPerFuelRod);

		if(attachedCoolantPorts.size() > 0) {
			coolantContainer.setCapacity(5000);
		}
		else {
			coolantContainer.setCapacity(0);
		}

		// Calculate derived stats
		
		// Calculate heat transfer based on fuel rod environment
		fuelToReactorHeatTransferCoefficient = 0f;
		for(TileEntityReactorFuelRod fuelRod : attachedFuelRods) {
			fuelToReactorHeatTransferCoefficient += fuelRod.getHeatTransferRate();
		}
		
		// Pick a random fuel rod Y as a starting point
		int maxFuelRodY = maxCoord.y - 1;
		int minFuelRodY = minCoord.y + 1;
		currentFuelRod = attachedFuelRods.iterator();
		
		// Calculate heat transfer to coolant system based on reactor interior surface area.
		// This is pretty simple to start with - surface area of the rectangular prism defining the interior.
		int xSize = maxCoord.x - minCoord.x - 1;
		int ySize = maxCoord.y - minCoord.y - 1;
		int zSize = maxCoord.z - minCoord.z - 1;
		
		int surfaceArea = 2 * (xSize * ySize + xSize * zSize + ySize * zSize);
		
		reactorToCoolantSystemHeatTransferCoefficient = IHeatEntity.conductivityIron * surfaceArea;

		// Calculate passive heat loss.
		// Get external surface area
		xSize += 2;
		ySize += 2;
		zSize += 3;
		
		surfaceArea = 2 * (xSize * ySize + xSize * zSize + ySize * zSize);
		reactorHeatLossCoefficient = reactorHeatLossConductivity * surfaceArea;
		
		if(worldObj.isRemote) {
			// Make sure our fuel rods re-render
			this.onFuelStatusChanged();
		}
		else {
			// Force an update of the client's multiblock information
			markReferenceCoordForUpdate();
		}
		
		calculateReactorVolume();
	}

	@Override
	protected int getMaximumXSize() {
		return BigReactors.maximumReactorSize;
	}

	@Override
	protected int getMaximumZSize() {
		return BigReactors.maximumReactorSize;
	}

	@Override
	protected int getMaximumYSize() {
		return BigReactors.maximumReactorHeight;
	}

	/**
	 * Used to update the UI
	 */
	public void setEnergyGeneratedLastTick(float energyGeneratedLastTick) {
		this.energyGeneratedLastTick = energyGeneratedLastTick;
	}

	/**
	 * UI Helper
	 */
	public float getEnergyGeneratedLastTick() {
		return this.energyGeneratedLastTick;
	}
	
	/**
	 * Used to update the UI
	 */
	public void setFuelConsumedLastTick(float fuelConsumed) {
		fuelConsumedLastTick = fuelConsumed;
	}
	
	/**
	 * UI Helper
	 */
	public float getFuelConsumedLastTick() {
		return fuelConsumedLastTick;
	}

	/**
	 * UI Helper
	 * @return Percentile fuel richness (fuel/fuel+waste), or 0 if all control rods are empty
	 */
	public float getFuelRichness() {
		int amtFuel, amtWaste;
		amtFuel = fuelContainer.getFuelAmount();
		amtWaste = fuelContainer.getWasteAmount();

		if(amtFuel + amtWaste <= 0f) { return 0f; }
		else { return (float)amtFuel / (float)(amtFuel+amtWaste); }
	}

	/** DO NOT USE **/
	@Override
	public int receiveEnergy(ForgeDirection from, int maxReceive,
			boolean simulate) {
		int amtReceived = (int)Math.min(maxReceive, Math.floor(this.maxEnergyStored - this.energyStored));
		if(!simulate) {
			this.addStoredEnergy(amtReceived);
		}
		return amtReceived;
	}

	@Override
	public int extractEnergy(ForgeDirection from, int maxExtract,
			boolean simulate) {
		int amtRemoved = (int)Math.min(maxExtract, this.energyStored);
		if(!simulate) {
			this.reduceStoredEnergy(amtRemoved);
		}
		return amtRemoved;
	}

	@Override
	public boolean canInterface(ForgeDirection from) {
		return false;
	}

	@Override
	public int getEnergyStored(ForgeDirection from) {
		return (int)energyStored;
	}

	@Override
	public int getMaxEnergyStored(ForgeDirection from) {
		return maxEnergyStored;
	}

	public int getMaxEnergyPerTick() {
		return this.maxEnergyStored;
	}
	
	// Redstone helper
	public void setAllControlRodInsertionValues(int newValue) {
		if(this.assemblyState != AssemblyState.Assembled) { return; }
		
		for(TileEntityReactorControlRod cr : attachedControlRods) {
			if(cr != null && cr.isConnected()) {
				cr.setControlRodInsertion((short)newValue);
			}
		}
	}
	
	public void changeAllControlRodInsertionValues(short delta) {
		if(this.assemblyState != AssemblyState.Assembled) { return; }
		
		for(TileEntityReactorControlRod cr : attachedControlRods) {
			if(cr != null && cr.isConnected()) {
				cr.setControlRodInsertion( (short) (cr.getControlRodInsertion() + delta) );
			}
		}
	}

	public CoordTriplet[] getControlRodLocations() {
		CoordTriplet[] coords = new CoordTriplet[this.attachedControlRods.size()];
		int i = 0;
		for(TileEntityReactorControlRod cr : attachedControlRods) {
			coords[i++] = cr.getWorldLocation();
		}
		return coords;
	}

	public int getFuelAmount() {
		return fuelContainer.getFuelAmount();
	}

	public int getWasteAmount() {
		return fuelContainer.getWasteAmount();
	}
	
	public Fluid getFuelType() {
		return fuelContainer.getFuelType();
	}
	
	public Fluid getWasteType() {
		return fuelContainer.getWasteType();
	}

	public int getEnergyStoredPercentage() {
		return (int)(this.energyStored / (float)this.maxEnergyStored * 100f);
	}

	@Override
	public int getCapacity() {
		if(worldObj.isRemote && assemblyState != AssemblyState.Assembled) {
			// Estimate capacity
			return attachedFuelRods.size() * FuelCapacityPerFuelRod;
		}

		return fuelContainer.getCapacity();
	}
	
	public float getFuelFertility() {
		return radiationHelper.getFertilityModifier();
	}
	
	// Coolant subsystem
	public CoolantContainer getCoolantContainer() {
		return coolantContainer;
	}
	
	protected float getPassiveCoolantTemperature() {
		return IHeatEntity.ambientHeat;
	}

	protected float getCoolantTemperature() {
		if(isPassivelyCooled()) {
			return getPassiveCoolantTemperature();
		}
		else {
			return coolantContainer.getCoolantTemperature(getReactorHeat());
		}
	}
	
	public boolean isPassivelyCooled() {
		if(coolantContainer == null || coolantContainer.getCapacity() <= 0) { return true; }
		else { return false; }
	}
	
	protected int getReactorVolume() {
		return reactorVolume;
	}
	
	protected void calculateReactorVolume() {
		CoordTriplet minInteriorCoord = getMinimumCoord();
		minInteriorCoord.x += 1;
		minInteriorCoord.y += 1;
		minInteriorCoord.z += 1;
		
		CoordTriplet maxInteriorCoord = getMaximumCoord();
		maxInteriorCoord.x -= 1;
		maxInteriorCoord.y -= 1;
		maxInteriorCoord.z -= 1;
		
		reactorVolume = StaticUtils.ExtraMath.Volume(minInteriorCoord, maxInteriorCoord);
	}

	// Client-only
	protected void onFuelStatusChanged() {
		if(worldObj.isRemote) {
			// On the client, re-render all the fuel rod blocks when the fuel status changes
			for(TileEntityReactorFuelRod fuelRod : attachedFuelRods) {
				worldObj.markBlockForUpdate(fuelRod.xCoord, fuelRod.yCoord, fuelRod.zCoord);
			}
		}
	}

	private static final FluidTankInfo[] emptyTankInfo = new FluidTankInfo[0];
	
	@Override
	public FluidTankInfo[] getTankInfo() {
		if(isPassivelyCooled()) { return emptyTankInfo; }
		
		return coolantContainer.getTankInfo(-1);
	}
	
	protected void markReferenceCoordForUpdate() {
		CoordTriplet rc = getReferenceCoord();
		if(worldObj != null && rc != null) {
			worldObj.markBlockForUpdate(rc.x, rc.y, rc.z);
		}
	}
	
	protected void markReferenceCoordDirty() {
		if(worldObj == null || worldObj.isRemote) { return; }

		CoordTriplet referenceCoord = getReferenceCoord();
		if(referenceCoord == null) { return; }

		TileEntity saveTe = worldObj.getBlockTileEntity(referenceCoord.x, referenceCoord.y, referenceCoord.z);
		worldObj.markTileEntityChunkModified(referenceCoord.x, referenceCoord.y, referenceCoord.z, saveTe);
	}
}
