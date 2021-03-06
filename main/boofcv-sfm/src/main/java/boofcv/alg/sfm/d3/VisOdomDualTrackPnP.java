/*
 * Copyright (c) 2011-2020, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.sfm.d3;

import boofcv.abst.feature.associate.AssociateDescription2D;
import boofcv.abst.feature.describe.DescribeRegionPoint;
import boofcv.abst.geo.Triangulate2ViewsMetric;
import boofcv.abst.tracker.PointTrack;
import boofcv.abst.tracker.PointTracker;
import boofcv.alg.feature.associate.StereoConsistencyCheck;
import boofcv.factory.distort.LensDistortionFactory;
import boofcv.struct.calib.StereoParameters;
import boofcv.struct.distort.Point2Transform2_F64;
import boofcv.struct.feature.AssociatedIndex;
import boofcv.struct.feature.TupleDesc;
import boofcv.struct.image.ImageBase;
import boofcv.struct.sfm.Stereo2D3D;
import georegression.struct.point.Point2D_F64;
import georegression.struct.point.Point3D_F64;
import georegression.struct.se.Se3_F64;
import georegression.transform.se.SePointOps_F64;
import org.ddogleg.fitting.modelset.ModelFitter;
import org.ddogleg.fitting.modelset.ModelMatcher;
import org.ddogleg.struct.FastAccess;
import org.ddogleg.struct.FastArray;
import org.ddogleg.struct.FastQueue;
import org.ddogleg.struct.GrowQueue_I32;

import java.util.ArrayList;
import java.util.List;

/**
 * Stereo visual odometry algorithm which relies on tracking features independently in the left and right images
 * and then matching those tracks together.  The idea behind this tracker is that the expensive task of association
 * features between left and right cameras only needs to be done once eat time a track is spawned.  Triangulation
 * is used to estimate each feature's 3D location.  Motion is estimated robustly using a RANSAC type algorithm
 * provided by the user which internally uses {@link boofcv.abst.geo.Estimate1ofPnP PnP} type algorithm.
 *
 * Estimated motion is relative to left camera.
 *
 * @author Peter Abeles
 */
public class VisOdomDualTrackPnP<T extends ImageBase<T>,Desc extends TupleDesc> {

	// Left and right input images
	private T inputLeft;
	private T inputRight;

	// when the inlier set is less than this number new features are detected
	private int thresholdAdd;

	// discard tracks after they have not been in the inlier set for this many updates in a row
	private int thresholdRetire;

	// computes camera motion
	private ModelMatcher<Se3_F64, Stereo2D3D> matcher;
	private ModelFitter<Se3_F64, Stereo2D3D> modelRefiner;

	// trackers for left and right cameras
	private PointTracker<T> trackerLeft;
	private PointTracker<T> trackerRight;
	private DescribeRegionPoint<T,Desc> describe;
	private double describeRadius=11.0;

	// Data structures used when associating left and right cameras
	private FastArray<Point2D_F64> pointsLeft = new FastArray<>(Point2D_F64.class);
	private FastArray<Point2D_F64> pointsRight = new FastArray<>(Point2D_F64.class);
	private FastQueue<Desc> descLeft,descRight;

	// matches features between left and right images
	private AssociateDescription2D<Desc> assocL2R;
	// Estimates the 3D coordinate of a feature
	private Triangulate2ViewsMetric triangulate;

	// convert for original image pixels into normalized image coordinates
	private Point2Transform2_F64 leftImageToNorm;
	private Point2Transform2_F64 rightImageToNorm;

	// Ensures that the epipolar constraint still applies to the tracks
	private StereoConsistencyCheck stereoCheck;

	// known stereo baseline
	private Se3_F64 leftToRight = new Se3_F64();

	// List of tracks from left image that remain after geometric filters have been applied
	private List<PointTrack> candidates = new ArrayList<>();

	// transform from key frame to world frame
	private Se3_F64 keyToWorld = new Se3_F64();
	// transform from the current camera view to the key frame
	private Se3_F64 currToKey = new Se3_F64();
	// transform from the current camera view to the world frame
	private Se3_F64 currToWorld = new Se3_F64();

	// is this the first frame
	private boolean first = true;

	/**
	 * Specifies internal algorithms and parameters
	 *
	 * @param thresholdAdd When the number of inliers is below this number new features are detected
	 * @param thresholdRetire When a feature has not been in the inlier list for this many ticks it is dropped
	 * @param epilolarTol Tolerance in pixels for enforcing the epipolar constraint
	 * @param trackerLeft Tracker used for left camera
	 * @param trackerRight Tracker used for right camera
	 * @param describe Describes features in tracks
	 * @param assocL2R Assocation for left to right
	 * @param triangulate Triangulation for estimating 3D location from stereo pair
	 * @param matcher Robust motion model estimation with outlier rejection
	 * @param modelRefiner Non-linear refinement of motion model
	 */
	public VisOdomDualTrackPnP(int thresholdAdd, int thresholdRetire, double epilolarTol,
							   PointTracker<T> trackerLeft, PointTracker<T> trackerRight,
							   DescribeRegionPoint<T,Desc> describe,
							   AssociateDescription2D<Desc> assocL2R,
							   Triangulate2ViewsMetric triangulate,
							   ModelMatcher<Se3_F64, Stereo2D3D> matcher,
							   ModelFitter<Se3_F64, Stereo2D3D> modelRefiner)
	{
		if( !assocL2R.uniqueSource() || !assocL2R.uniqueDestination() )
			throw new IllegalArgumentException("Both unique source and destination must be ensure by association");

		this.describe = describe;
		this.thresholdAdd = thresholdAdd;
		this.thresholdRetire = thresholdRetire;
		this.trackerLeft = trackerLeft;
		this.trackerRight = trackerRight;
		this.assocL2R = assocL2R;
		this.triangulate = triangulate;
		this.matcher = matcher;
		this.modelRefiner = modelRefiner;

		descLeft = new FastQueue<>(describe::createDescription);
		descRight = new FastQueue<>(describe::createDescription);

		stereoCheck = new StereoConsistencyCheck(epilolarTol,epilolarTol);
	}

	public void setCalibration(StereoParameters param) {

		param.rightToLeft.invert(leftToRight);
		leftImageToNorm = LensDistortionFactory.narrow(param.left).undistort_F64(true,false);
		rightImageToNorm = LensDistortionFactory.narrow(param.right).undistort_F64(true,false);
		stereoCheck.setCalibration(param);
	}

	/**
	 * Resets the algorithm into its original state
	 */
	public void reset() {
		trackerLeft.reset();
		trackerRight.reset();
		keyToWorld.reset();
		currToKey.reset();
		first = true;
	}

	/**
	 * Updates motion estimate using the stereo pair.
	 *
	 * @param left Image from left camera
	 * @param right Image from right camera
	 * @return true if motion estimate was updated and false if not
	 */
	public boolean process( T left , T right ) {
//		System.out.println("----------- Process --------------");

		this.inputLeft = left;
		this.inputRight = right;

		trackerLeft.process(left);
		trackerRight.process(right);

		if( first ) {
			addNewTracks();
			first = false;
		} else {
			mutualTrackDrop();
			selectCandidateTracks();
			boolean failed = !estimateMotion();
			dropUnusedTracks();

			if( failed )
				return false;

			int N = matcher.getMatchSet().size();

			if( modelRefiner != null )
				refineMotionEstimate();

			if( thresholdAdd <= 0 || N < thresholdAdd ) {
				changePoseToReference();
				addNewTracks();
			}
		}
		return true;
	}

	/**
	 * Non-linear refinement of motion estimate
	 */
	private void refineMotionEstimate() {

		// use observations from the inlier set
		List<Stereo2D3D> data = new ArrayList<>();

		int N = matcher.getMatchSet().size();
		for( int i = 0; i < N; i++ ) {
			int index = matcher.getInputIndex(i);

			PointTrack l = candidates.get(index);
			LeftTrackInfo info = l.getCookie();
			PointTrack r = info.right;

			Stereo2D3D stereo = info.location;
			// compute normalized image coordinate for track in left and right image
			leftImageToNorm.compute(l.pixel.x,l.pixel.y,info.location.leftObs);
			rightImageToNorm.compute(r.pixel.x,r.pixel.y,info.location.rightObs);

			data.add(stereo);
		}

		// refine the motion estimate using non-linear optimization
		Se3_F64 keyToCurr = currToKey.invert(null);
		Se3_F64 found = new Se3_F64();
		if( modelRefiner.fitModel(data,keyToCurr,found) ) {
			found.invert(currToKey);
		}
	}

	/**
	 * Given the set of active tracks, estimate the cameras motion robustly
	 * @return
	 */
	private boolean estimateMotion() {
		// organize the data
		List<Stereo2D3D> data = new ArrayList<>();

		for( PointTrack l : candidates ) {
			LeftTrackInfo info = l.getCookie();
			PointTrack r = info.right;

			Stereo2D3D stereo = info.location;
			// compute normalized image coordinate for track in left and right image
			leftImageToNorm.compute(l.pixel.x,l.pixel.y,info.location.leftObs);
			rightImageToNorm.compute(r.pixel.x,r.pixel.y,info.location.rightObs);

			data.add(stereo);
		}

		// Robustly estimate left camera motion
		if( !matcher.process(data) )
			return false;

		Se3_F64 keyToCurr = matcher.getModelParameters();
		keyToCurr.invert(currToKey);

		// mark tracks that are in the inlier set
		int N = matcher.getMatchSet().size();
		for( int i = 0; i < N; i++ ) {
			int index = matcher.getInputIndex(i);
			LeftTrackInfo info = candidates.get(index).getCookie();
			info.lastInlier = getFrameID();
		}

//		System.out.println("Inlier set size: "+N);

		return true;
	}

	/**
	 * If a track was dropped in one image make sure it was dropped in the other image
	 */
	private void mutualTrackDrop() {
		for( PointTrack t : trackerLeft.getDroppedTracks(null) ) {
			LeftTrackInfo info = t.getCookie();
			trackerRight.dropTrack(info.right);
		}
		for( PointTrack t : trackerRight.getDroppedTracks(null) ) {
			RightTrackInfo info = t.getCookie();
			// a track could be dropped twice here, such requests are ignored by the tracker
			trackerLeft.dropTrack(info.left);
		}
	}


	/**
	 * Searches for tracks which are active and meet the epipolar constraints
	 */
	private void selectCandidateTracks() {
		final long frameID = getFrameID();
		// mark tracks in right frame that are active
		List<PointTrack> activeRight = trackerRight.getActiveTracks(null);
		for( PointTrack t : activeRight ) {
			RightTrackInfo info = t.getCookie();
			info.lastActiveList = frameID;
		}

		int mutualActive = 0;
		List<PointTrack> activeLeft = trackerLeft.getActiveTracks(null);
		candidates.clear();
		for( PointTrack left : activeLeft ) {
			LeftTrackInfo info = left.getCookie();

//			if( info == null || info.right == null ) {
//				System.out.println("Oh Crap");
//			}

			// for each active left track, see if its right track has been marked as active
			RightTrackInfo infoRight = info.right.getCookie();
			if( infoRight.lastActiveList != frameID ) {
				continue;
			}

			// check epipolar constraint and see if it is still valid
			if( stereoCheck.checkPixel(left.pixel, info.right.pixel) ) {
				info.lastConsistent = frameID;
				candidates.add(left);
			}
			mutualActive++;
		}

//		System.out.println("Active Tracks: Left "+trackerLeft.getActiveTracks(null).size()+" right "+
//				trackerRight.getActiveTracks(null).size());
//		System.out.println("All Tracks:    Left "+trackerLeft.getAllTracks(null).size()+" right "+
//				trackerRight.getAllTracks(null).size());
//		System.out.println("Candidates = "+candidates.size()+" mutual active = "+mutualActive);
	}


	/**
	 * Removes tracks which have not been included in the inlier set recently
	 *
	 * @return Number of dropped tracks
	 */
	private int dropUnusedTracks() {
		long frameID = getFrameID();
		List<PointTrack> all = trackerLeft.getAllTracks(null);
		int num = 0;

		for( PointTrack t : all ) {
			LeftTrackInfo info = t.getCookie();
			if( frameID - info.lastInlier > thresholdRetire ) {
				if( !trackerLeft.dropTrack(t) )
					throw new IllegalArgumentException("failed to drop unused left track");
				if( !trackerRight.dropTrack(info.right) )
					throw new IllegalArgumentException("failed to drop unused right track");
				num++;
			}
		}
//		System.out.println("  total unused dropped "+num);

		return num;
	}

	/**
	 * Updates the relative position of all points so that the current frame is the reference frame.  Mathematically
	 * this is not needed, but should help keep numbers from getting too large.
	 */
	private void changePoseToReference() {
		Se3_F64 keyToCurr = currToKey.invert(null);

		List<PointTrack> all = trackerLeft.getAllTracks(null);

		for( PointTrack t : all ) {
			LeftTrackInfo p = t.getCookie();
			SePointOps_F64.transform(keyToCurr, p.location.location, p.location.location);
		}

		concatMotion();
	}

	/**
	 * Spawns tracks in each image and associates features together.
	 */
	private void addNewTracks() {
		final long frameID = getFrameID();
		trackerLeft.spawnTracks();
		trackerRight.spawnTracks();

		List<PointTrack> newLeft = trackerLeft.getNewTracks(null);
		List<PointTrack> newRight = trackerRight.getNewTracks(null);

		// get a list of new tracks and their descriptions
		addNewToList(inputLeft, newLeft, pointsLeft, descLeft);
		addNewToList(inputRight,newRight,pointsRight,descRight);

		// associate using L2R
		assocL2R.setSource(pointsLeft,descLeft);
		assocL2R.setDestination(pointsRight, descRight);
		assocL2R.associate();
		FastAccess<AssociatedIndex> matches = assocL2R.getMatches();

		// storage for the triangulated location in the camera frame
		Point3D_F64 cameraP3 = new Point3D_F64();

		for( int i = 0; i < matches.size; i++ ) {
			AssociatedIndex m = matches.get(i);

			PointTrack trackL = newLeft.get(m.src);
			PointTrack trackR = newRight.get(m.dst);

			// declare additional track information stored in each track.  Tracks can be recycled so it
			// might not always need to be declared
			LeftTrackInfo infoLeft = trackL.getCookie();
			if( infoLeft == null )
				trackL.cookie = infoLeft = new LeftTrackInfo();

			RightTrackInfo infoRight = trackR.getCookie();
			if( infoRight == null )
				trackR.cookie = infoRight = new RightTrackInfo();

			Stereo2D3D p2d3d = infoLeft.location;

			// convert pixel observations into normalized image coordinates
			leftImageToNorm.compute(trackL.pixel.x,trackL.pixel.y,p2d3d.leftObs);
			rightImageToNorm.compute(trackR.pixel.x,trackR.pixel.y,p2d3d.rightObs);

			// triangulate 3D coordinate in the current camera frame
			if( triangulate.triangulate(p2d3d.leftObs,p2d3d.rightObs,leftToRight,cameraP3) )
			{
				// put the track into the current keyframe coordinate system
				SePointOps_F64.transform(currToKey,cameraP3,p2d3d.location);
				// save a reference to the matching track in the right camera frame
				infoLeft.right = trackR;
				infoLeft.lastConsistent = infoLeft.lastInlier = frameID;
				infoRight.left = trackL;
			} else {
				// triangulation failed, drop track
				trackerLeft.dropTrack(trackL);
				// TODO need way to mark right tracks which are unassociated after this loop
				throw new RuntimeException("This special case needs to be handled!");
			}
		}

		// drop tracks that were not associated
		GrowQueue_I32 unassignedRight = assocL2R.getUnassociatedDestination();
		for( int i = 0; i < unassignedRight.size; i++ ) {
			int index = unassignedRight.get(i);
//			System.out.println(" unassigned right "+newRight.get(index).x+" "+newRight.get(index).y);
			trackerRight.dropTrack(newRight.get(index));
		}
		GrowQueue_I32 unassignedLeft = assocL2R.getUnassociatedSource();
		for( int i = 0; i < unassignedLeft.size; i++ ) {
			int index = unassignedLeft.get(i);
			trackerLeft.dropTrack(newLeft.get(index));
		}

//		System.out.println("Total left "+trackerLeft.getAllTracks(null).size()+"  right "+trackerRight.getAllTracks(null).size());

//		System.out.println("Associated: "+matches.size+" new left "+newLeft.size()+" new right "+newRight.size());
//		System.out.println("New Tracks: Total: Left "+trackerLeft.getAllTracks(null).size()+" right "+
//				trackerRight.getAllTracks(null).size());

//		List<PointTrack> temp = trackerLeft.getActiveTracks(null);
//		for( PointTrack t : temp ) {
//			if( t.cookie == null )
//				System.out.println("BUG!");
//		}
//		temp = trackerRight.getActiveTracks(null);
//		for( PointTrack t : temp ) {
//			if( t.cookie == null )
//				System.out.println("BUG!");
//		}
	}

	private void addNewToList( T image,
							   List<PointTrack> tracks ,
							   FastArray<Point2D_F64> points , FastQueue<Desc> descs )
	{
		describe.setImage(image);
		points.reset(); descs.reset();

		for( int i = 0; i < tracks.size(); i++ ) {
			PointTrack t = tracks.get(i);
			// ignoring the return value.  most descriptors never return false and the ones that due will rarely do so
			describe.process(t.pixel.x,t.pixel.y,0,describeRadius,descs.grow());

			points.add( t.pixel );
		}
	}


	private void concatMotion() {
		Se3_F64 temp = new Se3_F64();
		currToKey.concat(keyToWorld,temp);
		keyToWorld.set(temp);
		currToKey.reset();
	}

	public Se3_F64 getCurrToWorld() {
		currToKey.concat(keyToWorld, currToWorld);
		return currToWorld;
	}

	public long getFrameID() {
		return trackerLeft.getFrameID();
	}

	/**
	 * If there are no candidates then a fault happened.
	 * @return true if fault.  false is no fault
	 */
	public boolean isFault() {
		return candidates.isEmpty();
	}

	/**
	 * Returns a list of active tracks that passed geometric constraints
	 */
	public List<PointTrack> getCandidates() {
		return candidates;
	}

	public ModelMatcher<Se3_F64, Stereo2D3D> getMatcher() {
		return matcher;
	}

	public double getDescribeRadius() {
		return describeRadius;
	}

	public void setDescribeRadius(double describeRadius) {
		this.describeRadius = describeRadius;
	}

	public static class LeftTrackInfo
	{
		public Stereo2D3D location = new Stereo2D3D();
		// last time the track was declared as being geometrically consistent
		public long lastConsistent;
		// last time it was in the inlier list
		public long lastInlier;
		// right camera track it is associated with
		public PointTrack right;
	}

	public static class RightTrackInfo
	{
		// used to see if the right track is currently in the active list
		public long lastActiveList;
		// left camera track it is associated with
		public PointTrack left;
	}
}
