/*
 * Copyright (c) 2011-2017, Peter Abeles. All Rights Reserved.
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

package boofcv.alg.shapes.polyline.splitmerge;

import boofcv.alg.shapes.polyline.splitmerge.PolylineSplitMerge.Corner;
import boofcv.struct.ConfigLength;
import georegression.metric.Distance2D_F64;
import georegression.misc.GrlConstants;
import georegression.struct.line.LineParametric2D_F64;
import georegression.struct.line.LineSegment2D_F64;
import georegression.struct.point.Point2D_I32;
import org.ddogleg.struct.LinkedList.Element;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author Peter Abeles
 */
public class TestPolylineSplitMerge {

	@Test
	public void process_perfectSquare() {
		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.setCornerScorePenalty(0.1);
		alg.setMinimumSideLength(5);
		alg.setMaxNumberOfSideSamples(10);
		alg.setConvex(true);

		List<Point2D_I32> contour = rect(10,12,20,24);

		alg.process(contour);
		PolylineSplitMerge.CandidatePolyline result = alg.getBestPolyline();

		assertEquals(4,result.splits.size);
		assertEquals(0.1*4,result.score, 1e-8);

		// set a limit to the number of sides. This is a test in response to a bug
		alg.setMaxSides(4);
		alg.setExtraConsider(ConfigLength.fixed(2));
		assertTrue(alg.process(contour));
		result = alg.getBestPolyline();

		assertEquals(4,result.splits.size);
		assertEquals(0.1*4,result.score, 1e-8);
	}

	/**
	 * Data is a square but force it to match a triangle
	 */
	@Test
	public void process_perfectSquare_forcedTriangle() {
		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.setCornerScorePenalty(0.1);
		alg.setMinimumSideLength(5);
		alg.setMaxNumberOfSideSamples(10);
		alg.setConvex(true);
		alg.setMaxSides(3);
		alg.setExtraConsider(ConfigLength.fixed(2));
		alg.setMaxSideError(ConfigLength.fixed(1000)); // allow for a huge error

		List<Point2D_I32> contour = rect(10,12,20,24);

		assertTrue(alg.process(contour));
		PolylineSplitMerge.CandidatePolyline result = alg.getBestPolyline();
		assertEquals(3,result.splits.size);

		// make it have a stricter error test and it should fail
		alg.setMaxSideError(ConfigLength.fixed(1));
		assertFalse(alg.process(contour));
		assertTrue(null==alg.getBestPolyline());
	}

	@Test
	public void savePolyline() {
		PolylineSplitMerge alg = new PolylineSplitMerge();

		alg.getPolylines().grow();
		alg.getPolylines().grow();
		alg.getPolylines().grow();
		alg.getPolylines().grow();

		alg.addCorner(0).object.sideError = 10;
		alg.addCorner(0);
		alg.addCorner(0);
		alg.addCorner(0);

		alg.savePolyline();

		assertTrue(alg.getPolylines().get(1).score > 0);
		assertEquals(4,alg.getPolylines().get(1).splits.size);

		fail("update to check if saved or not");
	}

	@Test
	public void computeScore() {
		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.addCorner(0).object.sideError = 5;
		alg.addCorner(0).object.sideError = 6;
		alg.addCorner(0).object.sideError = 1;

		double expected = 12/3.0 + 0.5*3;
		double found = PolylineSplitMerge.computeScore(alg.list,0.5);

		assertEquals(expected,found,1e-8);
	}

	/**
	 * Give it an obvious triangle and see if it finds it
	 */
	@Test
	public void findInitialTriangle() {
		List<Point2D_I32> contour = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			contour.add(new Point2D_I32(i,i));
		}
		for (int i = 0; i < 10; i++) {
			contour.add(new Point2D_I32(9-i,9));
		}
		for (int i = 0; i < 8; i++) {
			contour.add(new Point2D_I32(0,8-i));
		}

		PolylineSplitMerge alg = new PolylineSplitMerge();

		assertTrue(alg.findInitialTriangle(contour));

		assertEquals(3,alg.list.size());

		// the order was specially selected knowing what the current algorithm is
		// te indexes are what it should be no matter what
		Element<Corner> e = alg.list.getHead();
		assertEquals(9,e.object.index);e = e.next;
		assertEquals(19,e.object.index);e = e.next;
		assertEquals(0,e.object.index);
	}

	@Test
	public void ensureTriangleOrder() {
		fail("implement");
	}

	/**
	 * Case where it should add a corner and then remove a corner
	 */
	@Test
	public void increaseNumberOfSidesByOne() {
		List<Point2D_I32> contour = rect(10,12,20,22);

		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.addCorner(5);
		alg.addCorner(10);
		alg.addCorner(20);
		alg.addCorner(30);

		// set up polyline variables
		Element<Corner> e = alg.list.getHead();
		while( e != null ) {
			e.object.splitable = false;
			e = e.next;
		}
		e = alg.list.getTail();
		e.object.splitable = true;
		e.object.sideError = alg.computeSideError(contour,e.object.index,5);
		alg.setSplitVariables(contour,e,alg.list.getHead());


		assertTrue(alg.increaseNumberOfSidesByOne(contour));

		assertEquals(4,alg.list.size());
		e = alg.list.getHead();
		assertEquals(0,e.object.sideError,1e-8);
		assertEquals(10,e.object.index);e = e.next;
		assertEquals(0,e.object.sideError,1e-8);
		assertEquals(20,e.object.index);e = e.next;
		assertEquals(0,e.object.sideError,1e-8);
		assertEquals(30,e.object.index);e = e.next;
		assertEquals(0,e.object.sideError,1e-8);
		assertEquals(0,e.object.index);
	}

	@Test
	public void isSideConvex() {
		fail("implement");
	}

	@Test
	public void selectCornerToSplit() {
		PolylineSplitMerge alg = new PolylineSplitMerge();
		Element<Corner> c0 = alg.addCorner(0);
		Element<Corner> c1 = alg.addCorner(10);
		Element<Corner> c2 = alg.addCorner(20);
		Element<Corner> c3 = alg.addCorner(30);
		Element<Corner> c4 = alg.addCorner(40);

		// net reduction of 2
		c2.object.splitable = true;
		c2.object.sideError = 6;
		c2.object.splitError0 = 1;
		c2.object.splitError1 = 3;
		// net reduction of 1
		c3.object.splitable = true;
		c3.object.sideError = 6;
		c3.object.splitError0 = 5;
		c3.object.splitError1 = 0;
		// small split error but an increase
		c1.object.splitable = true;
		c1.object.sideError = 2;
		c1.object.splitError0 = 1;
		c1.object.splitError1 = 2;
		//c0 is no change
		// massive reduction but marked as not splittable
		c4.object.splitable = false;
		c4.object.sideError = 20;

		assertTrue(c2==alg.selectCornerToSplit());

	}

	/**
	 * Test case where the corner is removed
	 */
	@Test
	public void selectAndRemoveCorner_positive() {
		List<Point2D_I32> contour = rect(10,12,20,18);

		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.setCornerScorePenalty(0.5);
		alg.addCorner(0);
		alg.addCorner(5);
		alg.addCorner(9);
		alg.addCorner(16);
		Element<Corner> e1 = alg.list.getHead();
		Element<Corner> e2 = e1.next;
		alg.getPolylines().grow(); // need to give a polyline to store the results in

		e1.object.sideError = 0;
		e2.object.sideError = 0;

		alg.selectAndRemoveCorner(contour);
		assertEquals(3,alg.list.size());
		fail("update. no longer given an edge");
	}

	/**
	 * Test case where the corner is NOT removed
	 */
	@Test
	public void selectAndRemoveCorner_negative() {
		List<Point2D_I32> contour = rect(10,12,20,18);

		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.setCornerScorePenalty(0.5);
		alg.addCorner(0);
		alg.addCorner(5);
		alg.addCorner(9);
		alg.addCorner(16);
		Element<Corner> e1 = alg.list.getHead().next;
		Element<Corner> e2 = e1.next;
		alg.getPolylines().grow(); // need to give a polyline to store the results in

		e1.object.sideError = 0;
		e2.object.sideError = 0;

		alg.selectAndRemoveCorner(contour);
		assertEquals(4,alg.list.size());
		fail("update. no longer given an edge");
	}

	@Test
	public void findCornerSeed() {
		List<Point2D_I32> contour = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			contour.add( new Point2D_I32(i,0));
		}
		contour.add( new Point2D_I32(2,1));
		contour.add( new Point2D_I32(2,2));

		assertEquals(19,PolylineSplitMerge.findCornerSeed(contour));
	}

	@Test
	public void maximumDistance() {
		fail("implement");
	}

	@Test
	public void computePotentialSplitScore() {
		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.setMinimumSideLength(5);
		alg.setThresholdSideSplitScore(0);

		List<Point2D_I32> contour = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			contour.add( new Point2D_I32(i,0));
		}

		// add some texture
		contour.get(3).y = 5;
		contour.get(15).y = 5;
		contour.get(10).y = 20; // this will be selected as the corner since it's the farthest away

		alg.addCorner(0);
		alg.addCorner(19);
		Element<Corner> e = alg.list.getHead();
		e.object.sideError =20;

		alg.computePotentialSplitScore(contour,e,false);

		assertTrue(e.object.splitable);
		assertTrue(e.object.splitError0 >0);
		assertTrue(e.object.splitError1 >0);
		assertEquals(10,e.object.splitLocation);
	}

	@Test
	public void computeSideError_exhaustive() {

		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.maxNumberOfSideSamples = 300; // have it exhaustively sample all pixels

		List<Point2D_I32> contour = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			contour.add( new Point2D_I32(i,0));
		}
		assertEquals(0,alg.computeSideError(contour,0,19), GrlConstants.TEST_F64);
		for (int i = 1; i < 19; i++) {
			contour.get(i).y = 5;
		}
		contour.get(10).y = 0; // need this to be zero so that two lines are the same
//		// average SSE
		double expected = (5.0*5.0*17)/18.0;
		assertEquals(expected,alg.computeSideError(contour,0,19), GrlConstants.TEST_F64);
		// the error should have this property to not bias it based on the number of sides
		expected = (5*5*9)/9.0 + (5*5*8)/8.0;
		double split = alg.computeSideError(contour,0,10)+alg.computeSideError(contour,10,19);
		assertEquals(expected,split, GrlConstants.TEST_F64);

		//----------- Test the wrapping around case
		List<Point2D_I32> contour2 = new ArrayList<>();
		for (int i = 0; i < contour.size(); i++) {
			contour2.add( contour.get((i+10)%contour.size()));
		}
		expected = (5*5*9)/9.0;
		assertEquals(expected,alg.computeSideError(contour2,10,0), GrlConstants.TEST_F64);
	}

	@Test
	public void computeSideError_skip() {
		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.maxNumberOfSideSamples = 5; // it will sub sample

		List<Point2D_I32> contour = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			contour.add( new Point2D_I32(i,0));
		}
		assertEquals(0,alg.computeSideError(contour,0,19), GrlConstants.TEST_F64);
		for (int i = 1; i < 19; i++) {
			contour.get(i).y = 5;
		}
		contour.get(10).y = 0;

		// see if it is within the expected by some error margin
		double expected = (5.0*5.0*17)/19.0;
		assertEquals(expected,alg.computeSideError(contour,0,19), expected*0.15);

		//----------- Now in the reverse direction
		List<Point2D_I32> contour2 = new ArrayList<>();
		for (int i = 0; i < contour.size(); i++) {
			contour2.add( contour.get((i+10)%contour.size()));
		}
		expected = (5*5*5)/5.0;
		assertEquals(expected,alg.computeSideError(contour2,10,0), GrlConstants.TEST_F64);
	}

	@Test
	public void addCorner() {
		PolylineSplitMerge alg = new PolylineSplitMerge();

		assertEquals(0,alg.list.size());
		alg.addCorner(3);
		assertEquals(1,alg.list.size());
		alg.addCorner(4);
		assertEquals(2,alg.list.size());
		assertEquals(3,alg.list.getElement(0,true).object.index);
		assertEquals(4,alg.list.getElement(1,true).object.index);
	}

	@Test
	public void setSplitVariables() {
		List<Point2D_I32> contour = rect(5,6,12,20);

		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.setConvex(false); // make sure this test doesn't get triggered

		// corners at 0,7,21,28
		alg.addCorner(0);
		alg.addCorner(21);
		alg.addCorner(28);

		Element<Corner> e = alg.list.getHead();
		// these values should be overwritten
		e.object.splitLocation = -1;
		e.object.splitError0 = -1;
		e.object.splitError1 = -1;

		alg.setSplitVariables(contour,e,alg.list.getElement(1,true));

		assertEquals(7,e.object.splitLocation);
		assertEquals(0,e.object.splitError0,1e-4);
		assertEquals(0,e.object.splitError1,1e-4);

		// turn on contour test. Should produce same results
		alg.setConvex(true);
		e.object.splitLocation = -1;
		e.object.splitError0 = -1;
		e.object.splitError1 = -1;
		alg.setSplitVariables(contour,e,alg.list.getElement(1,true));
		assertTrue(e.object.splitable);
		assertEquals(7,e.object.splitLocation);
		assertEquals(0,e.object.splitError0,1e-4);
		assertEquals(0,e.object.splitError1,1e-4);
	}

	/**
	 * The convex check is now enabled and should failed the split
	 */
	@Test
	public void setSplitVariables_withConvexCheck() {
		List<Point2D_I32> contour = rect(5,6,12,20);
		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.setConvex(true);

		// corners in reverse order to trigger convex failure
		Collections.reverse(contour);
		alg.addCorner(28);
		alg.addCorner(21);
		alg.addCorner(0);

		Element<Corner> e0 = alg.list.getHead().next;
		Element<Corner> e1 = e0.next;

		e1.object.splitable = true;

		alg.setSplitVariables(contour,e0,e1);

		assertFalse(e1.object.splitable);
	}

	@Test
	public void canBeSplit() {
		PolylineSplitMerge alg = new PolylineSplitMerge();

		// only the contour's size matters
		List<Point2D_I32> contour = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			contour.add( new Point2D_I32());
		}

		for (int i = 0; i < 10; i++) {
			Corner c = alg.corners.grow();
			c.index = i*5;
			c.sideError = 0.1; // give it an error greater than zero so that it will pass the side test
			alg.list.pushTail(c);
		}

		alg.setMinimumSideLength(5);
		alg.setThresholdSideSplitScore(0); // turn off this test

		assertTrue(alg.canBeSplit(contour,alg.list.getElement(5,true),false));
		assertTrue(alg.canBeSplit(contour,alg.list.getElement(9,true),false));

		alg.setMinimumSideLength(6);
		assertFalse(alg.canBeSplit(contour,alg.list.getElement(5,true),false));
		assertFalse(alg.canBeSplit(contour,alg.list.getElement(9,true),false));

		// test side split score
		alg.setMinimumSideLength(5);
		alg.setThresholdSideSplitScore(1);

		alg.list.getElement(5,true).object.sideError = 1;
		assertTrue(alg.canBeSplit(contour,alg.list.getElement(5,true),false));
		alg.list.getElement(5,true).object.sideError = 0.9999999;
		assertFalse(alg.canBeSplit(contour,alg.list.getElement(5,true),false));

		// test the must split flag
		alg.list.getElement(5,true).object.sideError = 0.9999999;
		assertTrue(alg.canBeSplit(contour,alg.list.getElement(5,true),true));
		alg.setMinimumSideLength(6);
		assertFalse(alg.canBeSplit(contour,alg.list.getElement(5,true),true));

	}

	/**
	 * Special case that requires wrapping around to compute the correct length
	 */
	@Test
	public void canBeSplit_special_case() {
		// only the contour's size matters
		List<Point2D_I32> contour = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			contour.add( new Point2D_I32());
		}

		PolylineSplitMerge alg = new PolylineSplitMerge();
		alg.setMinimumSideLength(5);
		alg.setThresholdSideSplitScore(0);

		alg.addCorner(0);
		alg.addCorner(10);
		alg.list.getHead().object.sideError = 0;
		alg.list.getTail().object.sideError = 0;

		assertTrue(alg.canBeSplit(contour,alg.list.getHead(),false));
		assertFalse(alg.canBeSplit(contour,alg.list.getTail(),false));
	}

	@Test
	public void next() {
		PolylineSplitMerge alg = new PolylineSplitMerge();

		Corner a = alg.corners.grow();
		Corner b = alg.corners.grow();
		Corner c = alg.corners.grow();

		alg.list.pushTail(a);
		alg.list.pushTail(b);
		alg.list.pushTail(c);

		assertTrue(c==alg.next(alg.list.find(b)).object);
		assertTrue(a==alg.next(alg.list.find(c)).object);
	}

	@Test
	public void previous() {
		PolylineSplitMerge alg = new PolylineSplitMerge();

		Corner a = alg.corners.grow();
		Corner b = alg.corners.grow();
		Corner c = alg.corners.grow();

		alg.list.pushTail(a);
		alg.list.pushTail(b);
		alg.list.pushTail(c);

		assertTrue(a==alg.previous(alg.list.find(b)).object);
		assertTrue(c==alg.previous(alg.list.find(a)).object);
	}

	/**
	 * Create a rectangle and feed it every point in the rectangle and see if it has the expected response
	 */
	@Test
	public void isConvexUsingMaxDistantPoints_positive() {
		List<Point2D_I32> contour = rect(5,6,12,20);

		for (int i = 0; i < contour.size(); i++) {
			int farthest = -1;
			double distance = -1;

			for (int j = 0; j < contour.size(); j++) {
				double d = contour.get(i).distance(contour.get(j));
				if( d > distance ) {
					distance = d;
					farthest = j;
				}
			}

			assertTrue( PolylineSplitMerge.isConvexUsingMaxDistantPoints(contour,i,farthest));
		}
	}

	/**
	 * Give it a scenario where it should fail
	 */
	@Test
	public void isConvexUsingMaxDistantPoints_negative() {
		List<Point2D_I32> contour = rect(5,6,12,20);
		// have the line segment it';s
		assertFalse( PolylineSplitMerge.isConvexUsingMaxDistantPoints(contour,2,3));
	}

	@Test
	public void distanceSq() {
		Point2D_I32 a = new Point2D_I32(2,4);
		Point2D_I32 b = new Point2D_I32( 10,-3);

		int expected = a.distance2(b);
		double found = PolylineSplitMerge.distanceSq(a,b);

		assertEquals(expected,found,GrlConstants.TEST_F64);
	}

	@Test
	public void distanceAbs() {
		Point2D_I32 a = new Point2D_I32(2,4);
		Point2D_I32 b = new Point2D_I32( 10,-3);

		int expected = Math.abs(2-10) + Math.abs(4+3);
		double found = PolylineSplitMerge.distanceAbs(a,b);

		assertEquals(expected,found,GrlConstants.TEST_F64);
	}


	@Test
	public void assignLine_parametric() {
		List<Point2D_I32> contour = new ArrayList<>();

		for (int i = 0; i < 20; i++) {
			contour.add( new Point2D_I32(i,2));
		}

		// make these points offset from all the others. That way if it grabs the wrong points the line will be wrong
		contour.get(1).set(1,5);
		contour.get(9).set(9,5);

		LineParametric2D_F64 line = new LineParametric2D_F64();

		PolylineSplitMerge.assignLine(contour,1,9,line);

		assertEquals(0, Distance2D_F64.distanceSq(line,0,5), GrlConstants.TEST_F64);
		assertEquals(0, Distance2D_F64.distanceSq(line,1,5), GrlConstants.TEST_F64);
		assertEquals(0, Distance2D_F64.distanceSq(line,9,5), GrlConstants.TEST_F64);

	}

	@Test
	public void assignLine_segment() {
		List<Point2D_I32> contour = new ArrayList<>();

		for (int i = 0; i < 20; i++) {
			contour.add( new Point2D_I32(i,2));
		}

		// make these points offset from all the others. That way if it grabs the wrong points the line will be wrong
		contour.get(1).set(1,5);
		contour.get(9).set(9,5);

		LineSegment2D_F64 line = new LineSegment2D_F64();

		PolylineSplitMerge.assignLine(contour,1,9,line);

		assertEquals(1, Distance2D_F64.distanceSq(line,0,5), GrlConstants.TEST_F64);
		assertEquals(0, Distance2D_F64.distanceSq(line,2,5), GrlConstants.TEST_F64);
		assertEquals(0, Distance2D_F64.distanceSq(line,8,5), GrlConstants.TEST_F64);

	}

	public static List<Point2D_I32> rect( int x0 , int y0 , int x1 , int y1 ) {
		List<Point2D_I32> out = new ArrayList<>();

		out.addAll( line(x0,y0,x1,y0));
		out.addAll( line(x1,y0,x1,y1));
		out.addAll( line(x1,y1,x0,y1));
		out.addAll( line(x0,y1,x0,y0));

		return out;
	}

	private static List<Point2D_I32> line( int x0 , int y0 , int x1 , int y1 ) {
		List<Point2D_I32> out = new ArrayList<>();

		int lengthY = Math.abs(y1-y0);
		int lengthX = Math.abs(x1-x0);

		int x,y;
		if( lengthY > lengthX ) {
			for (int i = 0; i < lengthY; i++) {
				x = x0 + (x1-x0)*lengthX*i/lengthY;
				y = y0 + (y1-y0)*i/lengthY;
				out.add( new Point2D_I32(x,y));
			}
		} else {
			for (int i = 0; i < lengthX; i++) {
				x = x0 + (x1-x0)*i/lengthX;
				y = y0 + (y1-y0)*lengthY*i/lengthX;
				out.add( new Point2D_I32(x,y));
			}
		}
		return out;
	}
}