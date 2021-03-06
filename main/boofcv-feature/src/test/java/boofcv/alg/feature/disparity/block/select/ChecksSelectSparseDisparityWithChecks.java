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

package boofcv.alg.feature.disparity.block.select;

import boofcv.alg.feature.disparity.block.SelectSparseStandardWta;
import org.junit.jupiter.api.Test;

import static boofcv.alg.feature.disparity.block.select.ChecksSelectDisparity.copyToCorrectType;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for sparse disparity checks
 *
 * @author Peter Abeles
 */
@SuppressWarnings("WeakerAccess")
public abstract class ChecksSelectSparseDisparityWithChecks<ArrayData>
{

	Class<ArrayData> arrayType;

	protected ChecksSelectSparseDisparityWithChecks(Class<ArrayData> arrayType) {
		this.arrayType = arrayType;
	}

	protected abstract SelectSparseStandardWta<ArrayData> createAlg(int maxError, double texture);

	/**
	 * Given an error return a score that's appropriate for the algorithm
	 */
	protected abstract int convertErrorToScore( int error );

	/**
	 * All validation tests are turned off
	 */
	@Test
	void everythingOff() {
		int maxDisparity = 30;

		int[] scores = new int[50];
		for( int i = 0; i < maxDisparity; i++) {
			scores[i] = convertErrorToScore(Math.abs(i-5)+2);
		}
		// if texture is left on then this will trigger bad stuff
		scores[8]=convertErrorToScore(3);

		SelectSparseStandardWta<ArrayData> alg = createAlg(-1,-1);

		assertTrue(alg.select(copyToCorrectType(scores,arrayType),maxDisparity));

		assertEquals(5,(int)alg.getDisparity());
	}

	/**
	 * Test the confidence in a region with very similar cost score (little texture)
	 */
	@Test
	void confidenceFlatRegion() {
		int minValue = 3;
		int maxDisparity=10;

		SelectSparseStandardWta<ArrayData> alg = createAlg(-1,1.0);

		int[] scores = new int[maxDisparity+10];

		for( int d = 0; d < 10; d++ ) {
			scores[d] = convertErrorToScore(minValue + Math.abs(2-d));
		}

		assertFalse(alg.select(copyToCorrectType(scores,arrayType), maxDisparity));
	}

	/**
	 * There are two similar peaks.  Repeated pattern
	 */
	@Test
	void confidenceMultiplePeak() {
		confidenceMultiplePeak(3);
		confidenceMultiplePeak(0);
	}

	private void confidenceMultiplePeak(int minValue ) {
		int maxDisparity=15;

		SelectSparseStandardWta<ArrayData> alg = createAlg(-1,0.5);

		int[] scores = new int[maxDisparity+10];

		for( int d = 0; d < maxDisparity; d++ ) {
			scores[d] = convertErrorToScore(minValue + (d % 5));
		}

		assertFalse(alg.select(copyToCorrectType(scores,arrayType), maxDisparity));
	}

	/**
	 * See if multiple peak detection works correctly when the first peak is at zero.  There was a bug related to
	 * this at one point.
	 */
	@Test
	void multiplePeakFirstAtIndexZero() {
		int maxDisparity=10;
		SelectSparseStandardWta<ArrayData> alg = createAlg(-1,0.1);
		int[] scores = new int[maxDisparity+10];

		for( int d = 0; d < 10; d++ ) {
			scores[d] = convertErrorToScore(d*2+1);
		}

		assertTrue(alg.select(copyToCorrectType(scores,arrayType), maxDisparity));
	}

	public static abstract class CheckError<ArrayData> extends ChecksSelectSparseDisparityWithChecks<ArrayData>
	{
		protected CheckError(Class<ArrayData> arrayType) {
			super(arrayType);
		}

		@Override
		protected int convertErrorToScore(int error) {
			return error;
		}
	}

	public static abstract class CheckCorrelation extends ChecksSelectSparseDisparityWithChecks<float[]>
	{
		protected CheckCorrelation() {
			super(float[].class);
		}

		@Override
		protected int convertErrorToScore(int error) {
			return -error;
		}
	}
}
