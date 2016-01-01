/*******************************************************************************
 * Copyright (c) 2015 Sebastian Stenzel and others.
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.crypto.engine.impl;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Assert;
import org.junit.Test;

public class FifoParallelDataProcessorTest {

	@Test(expected = IllegalStateException.class)
	public void testRethrowsException() throws InterruptedException {
		FifoParallelDataProcessor<Object> processor = new FifoParallelDataProcessor<>(1, 1);
		try {
			processor.submit(() -> {
				throw new IllegalStateException("will be rethrown during 'processedData()'");
			});
		} catch (Exception e) {
			Assert.fail("Exception must not yet be thrown.");
		}
		processor.processedData();
	}

	@Test(expected = RuntimeException.class)
	public void testRethrowsCheckedExceptionAsRuntimeExceptions() throws InterruptedException {
		FifoParallelDataProcessor<Object> processor = new FifoParallelDataProcessor<>(1, 1);
		try {
			processor.submit(() -> {
				throw new Exception("will be wrapped in a RuntimeException during 'processedData()'");
			});
		} catch (Exception e) {
			Assert.fail("Exception must not yet be thrown.");
		}
		processor.processedData();
	}

	@Test(expected = RejectedExecutionException.class)
	public void testRejectExecutionAfterShutdown() throws InterruptedException, ReflectiveOperationException, SecurityException {
		FifoParallelDataProcessor<Integer> processor = new FifoParallelDataProcessor<>(1, 1);
		Field field = FifoParallelDataProcessor.class.getDeclaredField("executorService");
		field.setAccessible(true);
		ExecutorService executorService = (ExecutorService) field.get(processor);
		executorService.shutdownNow();
		processor.submit(new IntegerJob(0, 1));
	}

	@Test
	public void testStrictFifoOrder() throws InterruptedException {
		FifoParallelDataProcessor<Integer> processor = new FifoParallelDataProcessor<>(4, 10);
		processor.submit(new IntegerJob(100, 1));
		processor.submit(new IntegerJob(50, 2));
		processor.submitPreprocessed(3);
		processor.submit(new IntegerJob(10, 4));
		processor.submit(new IntegerJob(10, 5));
		processor.submitPreprocessed(6);

		Assert.assertEquals(1, (int) processor.processedData());
		Assert.assertEquals(2, (int) processor.processedData());
		Assert.assertEquals(3, (int) processor.processedData());
		Assert.assertEquals(4, (int) processor.processedData());
		Assert.assertEquals(5, (int) processor.processedData());
		Assert.assertEquals(6, (int) processor.processedData());
	}

	@Test
	public void testBlockingBehaviour() throws InterruptedException {
		FifoParallelDataProcessor<Integer> processor = new FifoParallelDataProcessor<>(1, 1);
		processor.submitPreprocessed(1); // #1 in queue

		Thread t1 = new Thread(() -> {
			try {
				processor.submitPreprocessed(2); // #2 in queue
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		t1.start();
		t1.join(10);
		// job 2 should not have been submitted by now, thus t1 is still alive
		Assert.assertTrue(t1.isAlive());
		Assert.assertEquals(1, (int) processor.processedData());
		Assert.assertEquals(2, (int) processor.processedData());
		t1.join();
	}

	@Test
	public void testInterruptionDuringSubmission() throws InterruptedException {
		FifoParallelDataProcessor<Integer> processor = new FifoParallelDataProcessor<>(1, 1);
		processor.submitPreprocessed(1); // #1 in queue

		final AtomicBoolean interruptedExceptionThrown = new AtomicBoolean(false);
		Thread t1 = new Thread(() -> {
			try {
				processor.submitPreprocessed(2); // #2 in queue
			} catch (InterruptedException e) {
				interruptedExceptionThrown.set(true);
				Thread.currentThread().interrupt();
			}
		});
		t1.start();
		t1.join(10);
		t1.interrupt();
		t1.join(10);
		// job 2 should not have been submitted by now, thus t1 is still alive
		Assert.assertFalse(t1.isAlive());
		Assert.assertTrue(interruptedExceptionThrown.get());
		Assert.assertEquals(1, (int) processor.processedData());
	}

	private static class IntegerJob implements Callable<Integer> {

		private final long waitMillis;
		private final int result;

		public IntegerJob(long waitMillis, int result) {
			this.waitMillis = waitMillis;
			this.result = result;
		}

		@Override
		public Integer call() throws Exception {
			Thread.sleep(waitMillis);
			return result;
		}

	}

}