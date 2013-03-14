package org.millenaire.common;
import java.util.HashMap;
import java.util.Random;
import java.util.Vector;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.millenaire.common.MLN.MillenaireException;
import org.millenaire.common.construction.BuildingPlan;
import org.millenaire.common.construction.BuildingPlan.LocationBuildingPair;
import org.millenaire.common.construction.BuildingPlan.LocationReturn;
import org.millenaire.common.construction.BuildingPlanSet;
import org.millenaire.common.core.MillCommonUtilities;
import org.millenaire.common.forge.Mill;
import org.millenaire.common.network.ServerSender;
import org.millenaire.common.pathing.AStarPathing;

import cpw.mods.fml.common.IWorldGenerator;



public class WorldGenVillage implements IWorldGenerator {


	private static final double MINIMUM_USABLE_BLOCK_PERC = 0.7;

	static public Vector<Integer> coordsTried=new Vector<Integer>();

	public static boolean generateBedrockLoneBuilding(Point p,World world,VillageType village,Random random,int minDistance,int maxDistance) throws MillenaireException {

		if (world.isRemote)
			return false;

		if (p.horizontalDistanceTo(world.getSpawnPoint())<MLN.spawnProtectionRadius)
			return false;


		if (MLN.WorldGeneration>=MLN.MAJOR) {
			MLN.major(null,"Generating bedrockbuilding: "+village);
		}

		final BuildingPlan plan=village.centreBuilding.getRandomStartingPlan();
		BuildingLocation location=null;

		for (int i=0;(i<100) && (location==null);i++) {
			int x=minDistance+MillCommonUtilities.randomInt(maxDistance-minDistance);
			int z=minDistance+MillCommonUtilities.randomInt(maxDistance-minDistance);

			if (MillCommonUtilities.chanceOn(2)) {
				x=-x;
			}
			if (MillCommonUtilities.chanceOn(2)) {
				z=-z;
			}

			final LocationReturn lr=plan.testSpotBedrock(world, p.getiX()+x, p.getiZ()+z);
			location=lr.location;
		}

		if (location==null) {
			MLN.major(null,"No spot found for: "+village);

			int x=minDistance+MillCommonUtilities.randomInt(maxDistance-minDistance);
			int z=minDistance+MillCommonUtilities.randomInt(maxDistance-minDistance);

			if (MillCommonUtilities.chanceOn(2)) {
				x=-x;
			}
			if (MillCommonUtilities.chanceOn(2)) {
				z=-z;
			}

			location=new BuildingLocation(plan,new Point(p.getiX()+x,2, p.getiZ()+z),0);
			location.bedrocklevel=true;
		}

		final Vector<LocationBuildingPair> lbps=village.centreBuilding.buildLocation(Mill.getMillWorld(world),village,location,true, true, null, false, null);

		final Building townHallEntity=lbps.firstElement().building;

		if (MLN.WorldGeneration>=MLN.MAJOR) {
			MLN.major(null,"Registering building: "+townHallEntity);
		}
		townHallEntity.villageType=village;

		townHallEntity.findName(null);
		townHallEntity.initialiseBuildingProjects();
		townHallEntity.registerBuildingLocation(location);

		for (final LocationBuildingPair lbp : lbps) {
			if (lbp!=lbps.firstElement()) {
				townHallEntity.registerBuildingEntity(lbp.building);
				townHallEntity.registerBuildingLocation(lbp.location);
			}
		}
		townHallEntity.initialiseTownHallChestLocking();

		Mill.getMillWorld(world).registerLoneBuildingsLocation(world,townHallEntity.getPos(),townHallEntity.getVillageQualifiedName(),townHallEntity.villageType,townHallEntity.culture,true);

		MLN.major(null,"Finished bedrock building "+village+" at "+townHallEntity.getPos());

		return true;
	}

	@Override
	public void generate(Random random, int chunkX, int chunkZ, World world,
			IChunkProvider chunkGenerator, IChunkProvider chunkProvider) {

		if (world.getWorldInfo().getDimension()!=0)
			return;


		//Hack to check whether the generation is looping
		final StackTraceElement[] trace = Thread.currentThread().getStackTrace();

		for (int i=2;i<trace.length;i++) {
			if (trace[i].getClassName().equals(this.getClass().getName()))
				return;
		}

		try {
			generateVillageAtPoint(world, random, chunkX*16, 0, chunkZ*16, null,true,false,Integer.MAX_VALUE, null, null, null);
		} catch (final Exception e) {
			MLN.printException("Exception when attempting to generate village in "+world+" (dimension: "+world.getWorldInfo().getDimension()+")", e);
		}
	}

	private void generateHamlet(World world, VillageType hamlet,Point centralVillage,String name,Random random) {
		boolean generated=false;

		int minRadius=130;
		while (!generated && (minRadius<200)) {

			double angle=((2*3.14)/100)*MillCommonUtilities.randomInt(100);
			int attempts=0;

			while (!generated && (attempts < 300)) {
				angle+=(2*3.14)/300;
				final int radius=minRadius+MillCommonUtilities.randomInt(40);

				final int dx=(int) (Math.cos(angle)*radius);

				final int dz=(int) (Math.sin(angle)*radius);

				if (MLN.WorldGeneration>=MLN.MAJOR) {
					MLN.major(this,"Trying to generate a hamlet "+hamlet+" around: "+(centralVillage.getiX()+dx)+"/"+(centralVillage.getiZ()+dz));
				}
				generated=generateVillageAtPoint(world,random,centralVillage.getiX()+dx,0,centralVillage.getiZ()+dz,null,false,true,100,hamlet,name,centralVillage);

				attempts++;
			}
			minRadius+=50;
		}

		if (!generated && (MLN.WorldGeneration>=MLN.MAJOR)) {
			MLN.major(this, "Could not generate hamlet "+hamlet);
		}
	}

	private boolean generateVillage(Point p,World world,VillageType village,EntityPlayer player,Random random,int minDistance,String name,boolean loneBuildings, Point parentVillage) throws MillenaireException {

		long startTime;

		final MillWorldInfo winfo=new MillWorldInfo();
		final Vector<BuildingLocation> plannedBuildings = new Vector<BuildingLocation>();

		final MillWorld mw=Mill.getMillWorld(world);

		p=new Point(p.x,MillCommonUtilities.findTopSoilBlock(world, p.getiX(), p.getiZ()),p.z);

		winfo.update(world, plannedBuildings, null, p,village.radius);

		for (int x=p.getChunkX()-(village.radius/16)-1;x<=(p.getChunkX()+(village.radius/16));x++) {
			for (int z=p.getChunkZ()-(village.radius/16)-1;z<=(p.getChunkZ()+(village.radius/16));z++) {
				if (!world.getChunkFromChunkCoords(x, z).isChunkLoaded) {
					world.getChunkProvider().loadChunk(x, z);
				}
			}
		}

		if (player==null) {
			if (!isAppropriateArea(winfo,p,village.radius))
				return false;
		}

		startTime = System.nanoTime();

		BuildingLocation location=village.centreBuilding.getRandomStartingPlan().findBuildingLocation(winfo, null,p, village.radius, random, BuildingPlan.EAST_FACING);

		if (location==null) {
			if (MLN.WorldGeneration>=MLN.MINOR) {
				MLN.minor(this, "Could not find place for central building: "+village.centreBuilding);
			}

			if (player!=null) {
				ServerSender.sendTranslatedSentence(player, MLN.ORANGE, "ui.generatenotenoughspace");
			}
			return false;
		}

		if (MLN.WorldGeneration>=MLN.MINOR) {
			MLN.minor(this, "Place found for TownHall (village type: "+village.key+"). Checking for the rest.");
		}

		p=location.pos;

		plannedBuildings.add(location);
		winfo.update(world, plannedBuildings, null, p,village.radius);

		boolean couldBuildKeyBuildings=true;

		final AStarPathing pathing = new AStarPathing();

		pathing.createConnectionsTable(winfo,p);

		for (final BuildingPlanSet planSet : village.startBuildings) {
			location=planSet.getRandomStartingPlan().findBuildingLocation(winfo,pathing,p, village.radius, random, -1);
			if (location != null) {
				plannedBuildings.add(location);
				winfo.update(world, plannedBuildings, null, p,village.radius);
			} else {
				couldBuildKeyBuildings=false;
				if (MLN.WorldGeneration>=MLN.MINOR) {
					MLN.minor(this, "Couldn't build "+planSet.key+".");
				}
			}
		}

		if (MLN.WorldGeneration>=MLN.DEBUG) {
			MLN.debug(this,"Time taken for finding if building possible: "+(System.nanoTime()-startTime));
		}

		if (!couldBuildKeyBuildings) {
			if (player!=null) {
				ServerSender.sendTranslatedSentence(player, MLN.ORANGE, "ui.generatenotenoughspacevillage");
			}

			return false;
		}

		if (player==null) {

			int minDistanceWithVillages;
			int minDistanceWithLoneBuildings;

			if (loneBuildings) {
				if (village.isKeyLoneBuildingForGeneration(null)) {
					minDistanceWithVillages=Math.min(minDistance, MLN.minDistanceBetweenVillagesAndLoneBuildings)/2;
					minDistanceWithLoneBuildings=Math.min(minDistance, MLN.minDistanceBetweenLoneBuildings)/2;
				} else {
					minDistanceWithVillages=Math.min(minDistance, MLN.minDistanceBetweenVillagesAndLoneBuildings);
					minDistanceWithLoneBuildings=Math.min(minDistance, MLN.minDistanceBetweenLoneBuildings);
				}
			} else {
				minDistanceWithVillages=Math.min(minDistance, MLN.minDistanceBetweenVillages);
				minDistanceWithLoneBuildings=Math.min(minDistance, MLN.minDistanceBetweenVillagesAndLoneBuildings);
			}

			for (final Point thp : mw.villagesList.pos) {
				if (p.distanceTo(thp) < minDistanceWithVillages) {
					if (MLN.WorldGeneration>=MLN.MAJOR) {
						MLN.major(this,"Found a nearby village on second attempt.");
					}
					return false;
				}
			}

			for (final Point thp : mw.loneBuildingsList.pos) {
				if (p.distanceTo(thp) < minDistanceWithLoneBuildings) {
					if (MLN.WorldGeneration>=MLN.MAJOR) {
						MLN.major(this,"Found a nearby lone building on second attempt.");
					}
					return false;
				}
			}
		}

		if (MLN.WorldGeneration>=MLN.MAJOR) {
			MLN.major(this,p+": Generating village");
		}

		if (MLN.WorldGeneration>=MLN.MAJOR) {
			for (final BuildingLocation bl : plannedBuildings) {
				MLN.major(this,  "Building "+bl.key+": "+bl.minx+"/"+bl.minz+" to "+bl.maxx+"/"+bl.maxz);
			}
		}
		startTime = System.nanoTime();

		Vector<LocationBuildingPair> lbps=village.centreBuilding.buildLocation(mw,village,plannedBuildings.get(0),true, true, null, false, player);

		final Building townHallEntity=lbps.firstElement().building;

		if (MLN.WorldGeneration>=MLN.MAJOR) {
			MLN.major(this,"Registering building: "+townHallEntity);
		}
		townHallEntity.villageType=village;
		townHallEntity.findName(name);
		townHallEntity.initialiseBuildingProjects();
		townHallEntity.registerBuildingLocation(plannedBuildings.get(0));


		for (final LocationBuildingPair lbp : lbps) {
			if (lbp!=lbps.firstElement()) {

				townHallEntity.registerBuildingEntity(lbp.building);
				townHallEntity.registerBuildingLocation(lbp.location);
			}
		}

		for (int i=1;i<plannedBuildings.size();i++) {

			final BuildingLocation bl=plannedBuildings.get(i);

			lbps=village.culture.getBuildingPlanSet(bl.key).buildLocation(mw,village,bl,true, false, townHallEntity.getPos(), false, player);
			if (MLN.WorldGeneration>=MLN.MAJOR) {
				MLN.major(this,"Registering building: "+bl.key);
			}
			for (final LocationBuildingPair lbp : lbps) {

				townHallEntity.registerBuildingEntity(lbp.building);
				townHallEntity.registerBuildingLocation(lbp.location);
			}
		}

		townHallEntity.initialiseTownHallChestLocking();


		if (loneBuildings) {
			mw.registerLoneBuildingsLocation(world,townHallEntity.getPos(),townHallEntity.getVillageQualifiedName(),townHallEntity.villageType,townHallEntity.culture,true);
		} else {
			mw.registerVillageLocation(world,townHallEntity.getPos(),townHallEntity.getVillageQualifiedName(),townHallEntity.villageType,townHallEntity.culture,true);
			townHallEntity.initialiseRelations(parentVillage);
			if (village.playerControlled) {
				townHallEntity.storeGoods(Mill.parchmentVillageScroll.itemID, mw.villagesList.pos.size()-1, 1);

			}
		}

		if (MLN.WorldGeneration>=MLN.MAJOR) {
			MLN.major(this,"New village generated at "+p+", took: "+(System.nanoTime()-startTime));
		}

		for (final String key : village.hamlets) {

			final VillageType hamlet=village.culture.getVillageType(key);

			if (hamlet != null) {
				if (MLN.WorldGeneration>=MLN.MAJOR) {
					MLN.major(this,"Trying to generate a hamlet: "+hamlet);
				}
				generateHamlet(world,hamlet,townHallEntity.getPos(),townHallEntity.getVillageNameWithoutQualifier(),random);
			}
		}

		return true;
	}

	public boolean generateVillageAtPoint(World world, Random random, int x, int y, int z, EntityPlayer generatingPlayer, boolean checkForUnloaded, boolean alwaysGenerate,int minDistance, VillageType villageType, String name, Point parentVillage) {

		if (!Mill.loadingComplete || (!MLN.generateVillages && !MLN.generateLoneBuildings && !alwaysGenerate))
			return false;

		if (world.isRemote)
			return false;

		final MillWorld mw=Mill.getMillWorld(world);

		if (mw==null)
			return false;

		Point p=new Point(x,65,z);

		EntityPlayer closestPlayer=generatingPlayer;

		if (closestPlayer==null) {
			closestPlayer=world.getClosestPlayer(x, 64, z, 200);
		}


		try {

			if (MLN.WorldGeneration>=MLN.DEBUG) {
				MLN.debug(this, "Called for point: "+x+"/"+y+"/"+z);
			}

			MillCommonUtilities.random=random;

			boolean areaLoaded=false;

			long startTime;

			if (checkForUnloaded) {
				if (!world.checkChunksExist(x-(16*5), y, z-(16*5), x+(16*5), y, z+(16*5))) {//this area isn't ready
					//let us test other chunks close by:
					for (int i=-6;(i<7) && !areaLoaded;i++) {
						for (int j=-6;(j<7) && !areaLoaded;j++) {
							final int tx=x+(i*16),tz=z+(j*16);
							if (!coordsTried.contains(tx+(tz << 16))) {
								if (world.checkChunksExist(tx-(16*5), y, tz-(16*5), tx+(16*5), y, tz+(16*5))) {
									x=tx;
									z=tz;
									areaLoaded=true;
									final Point np=new Point(((x >> 4)*16)+8,0,((z >> 4)*16)+8);
									//Log.debug(p.getChunkString()+": area centred on "+np.getChunkString()+" loaded.");
									p=np;
								}
							}
						}
					}
				} else {
					areaLoaded=true;
				}

				if (!areaLoaded) {
					if (generatingPlayer!=null) {
						ServerSender.sendTranslatedSentence(generatingPlayer, MLN.ORANGE, "ui.worldnotgenerated");
					}
					return false;
				}

				if ((p.horizontalDistanceTo(world.getSpawnPoint())<MLN.spawnProtectionRadius) && Mill.proxy.isTrueServer()) {
					if (generatingPlayer!=null) {
						ServerSender.sendTranslatedSentence(generatingPlayer, MLN.ORANGE, "ui.tooclosetospawn");
					}
					return false;
				}
			}



			startTime = System.nanoTime();

			coordsTried.add(x+(z << 16));

			if (MLN.generateVillages || alwaysGenerate) {
				boolean canAttemptVillage=true;

				final int minDistanceVillages=Math.min(minDistance, MLN.minDistanceBetweenVillages);
				final int minDistanceLoneBuildings=Math.min(minDistance, MLN.minDistanceBetweenVillagesAndLoneBuildings);

				if (generatingPlayer==null) {

					if (p.horizontalDistanceTo(world.getSpawnPoint())<MLN.spawnProtectionRadius) {
						canAttemptVillage=false;
					}

					for (final Point thp : mw.villagesList.pos) {
						if (p.distanceTo(thp) < minDistanceVillages) {
							if (MLN.WorldGeneration>=MLN.DEBUG) {
								MLN.debug(this,"Time taken for finding near villages: "+(System.nanoTime()-startTime));
							}
							canAttemptVillage=false;
						}
					}

					for (final Point thp : mw.loneBuildingsList.pos) {
						if (p.distanceTo(thp) < minDistanceLoneBuildings) {
							if (MLN.WorldGeneration>=MLN.DEBUG) {
								MLN.debug(this,"Time taken for finding near lone buildings: "+(System.nanoTime()-startTime));
							}
							canAttemptVillage=false;
						}
					}
				}
				if (MLN.WorldGeneration>=MLN.DEBUG) {
					MLN.debug(this,"Time taken for finding near villages (not found): "+(System.nanoTime()-startTime));
				}

				if (canAttemptVillage) {

					VillageType village;

					if (villageType==null) {

						final String biomeName=world.getWorldChunkManager().getBiomeGenAt(x, z).biomeName.toLowerCase();

						final Vector<VillageType> acceptableVillageType=new Vector<VillageType>();

						final HashMap<String,Integer> nbVillages=new HashMap<String,Integer>();
						for (final String type : mw.villagesList.types) {
							if (nbVillages.containsKey(type)) {
								nbVillages.put(type, nbVillages.get(type)+1);
							} else {
								nbVillages.put(type, 1);
							}
						}

						for (final Culture c : Culture.vectorCultures) {
							for (final VillageType vt : c.vectorVillageTypes) {
								if (vt.isValidForGeneration(Mill.getMillWorld(world),closestPlayer,nbVillages, new Point(x,60,z), biomeName, false)) {
									acceptableVillageType.add(vt);
								}
							}
						}

						if (acceptableVillageType.size()!=0) {
							village=(VillageType)MillCommonUtilities.getWeightedChoice(acceptableVillageType,closestPlayer);
						} else {
							village=null;
						}

					} else {
						village=villageType;
					}

					if ((village!=null) && generateVillage(p,world,village,generatingPlayer,random,minDistance,name,false,parentVillage))
						return true;//village generated, stopping
				}
			}

			if ((generatingPlayer!=null) || !MLN.generateLoneBuildings)//no lone buildings when using the wand
				return false;

			if (villageType!=null)//no lone buildings when attempting a specific village type
				return false;

			boolean keyLoneBuildingsOnly=false;

			final int minDistanceWithVillages=Math.min(minDistance, MLN.minDistanceBetweenVillagesAndLoneBuildings);
			final int minDistanceWithLoneBuildings=Math.min(minDistance, MLN.minDistanceBetweenLoneBuildings);



			for (final Point thp : mw.villagesList.pos) {
				if (p.distanceTo(thp) < (minDistanceWithVillages/2)) {
					if (MLN.WorldGeneration>=MLN.DEBUG) {
						MLN.debug(this,"Time taken for finding near villages: "+(System.nanoTime()-startTime));
					}
					return false;
				} else if (p.distanceTo(thp) < minDistanceWithVillages) {
					keyLoneBuildingsOnly=true;//too close for anything except a key lone building
				}
			}

			for (final Point thp : mw.loneBuildingsList.pos) {
				if (p.distanceTo(thp) < (minDistanceWithLoneBuildings/2)) {
					if (MLN.WorldGeneration>=MLN.DEBUG) {
						MLN.debug(this,"Time taken for finding near villages: "+(System.nanoTime()-startTime));
					}
					return false;
				} else if (p.distanceTo(thp) < minDistanceWithLoneBuildings) {
					keyLoneBuildingsOnly=true;//too close for anything except a key lone building
				}
			}

			if (MLN.WorldGeneration>=MLN.DEBUG) {
				MLN.debug(this,"Time taken for finding near villages (not found): "+(System.nanoTime()-startTime));
			}
			
			

			final String biomeName=world.getWorldChunkManager().getBiomeGenAt(x, z).biomeName.toLowerCase();

			final Vector<VillageType> acceptableLoneBuildingsType=new Vector<VillageType>();

			//Calculating the existing number of buildings:
			final HashMap<String,Integer> nbLoneBuildings=new HashMap<String,Integer>();
			for (final String type : mw.loneBuildingsList.types) {
				if (nbLoneBuildings.containsKey(type)) {
					nbLoneBuildings.put(type, nbLoneBuildings.get(type)+1);
				} else {
					nbLoneBuildings.put(type, 1);
				}
			}

			for (final Culture c : Culture.vectorCultures) {
				for (final VillageType vt : c.vectorLoneBuildingTypes) {
					if (vt.isValidForGeneration(mw,closestPlayer,nbLoneBuildings, new Point(x,60,z), biomeName, keyLoneBuildingsOnly)) {
						acceptableLoneBuildingsType.add(vt);
					}
				}
			}

			if (acceptableLoneBuildingsType.size()==0)
				return false;

			final VillageType loneBuilding=(VillageType)MillCommonUtilities.getWeightedChoice(acceptableLoneBuildingsType,closestPlayer);

			if (MLN.WorldGeneration>=MLN.MINOR) {
				MLN.minor(null, "Attempting to find lone building: "+loneBuilding);
			}

			if (loneBuilding==null)
				return false;

			if (loneBuilding.isKeyLoneBuildingForGeneration(closestPlayer)) {
				if (MLN.WorldGeneration>=MLN.MAJOR) {
					MLN.major(null, "Attempting to generate key lone building: "+loneBuilding.key);
				}
			}

			final boolean success= generateVillage(p,world,loneBuilding,generatingPlayer,random,minDistance,name,true,null);
			
			if (success && (closestPlayer!=null) &&
					loneBuilding.isKeyLoneBuildingForGeneration(closestPlayer) && (loneBuilding.keyLoneBuildingGenerateTag!=null)) {

				final UserProfile profile=mw.getProfile(closestPlayer.username);
				profile.clearTag(loneBuilding.keyLoneBuildingGenerateTag);
			}

			return success;
		} catch (final Exception e) {
			MLN.printException("Exception when generating village:",e);
		}

		return false;
	}

	private boolean isAppropriateArea(MillWorldInfo winfo,Point centre, int radius) {

		int nbtiles=0,usabletiles=0;

		for (int i=0;i<winfo.length;i++) {
			for (int j=0;j<winfo.width;j++){
				nbtiles++;
				if (winfo.canBuild[i][j]) {
					usabletiles++;
				}
			}
		}

		return (((usabletiles*1.0)/nbtiles) > MINIMUM_USABLE_BLOCK_PERC);
	}

	@Override
	public String toString() {
		return getClass().getSimpleName()+"@"+hashCode();
	}


}
