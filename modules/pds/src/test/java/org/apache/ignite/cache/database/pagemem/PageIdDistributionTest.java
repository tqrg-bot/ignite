package org.apache.ignite.cache.database.pagemem;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.ignite.internal.mem.DirectMemoryRegion;
import org.apache.ignite.internal.mem.unsafe.UnsafeMemoryProvider;
import org.apache.ignite.internal.pagemem.FullPageId;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.processors.cache.database.pagemem.FullPageIdTable;
import org.apache.ignite.internal.processors.cache.database.pagemem.PageMemoryImpl;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

/**
 *
 */
public class PageIdDistributionTest extends GridCommonAbstractTest {
    /** */
    private static final int[] CACHE_IDS = new int[] {
        CU.cacheId("partitioned1"),
        CU.cacheId("partitioned2"),
        CU.cacheId("partitioned3"),
        CU.cacheId("partitioned4"),
        CU.cacheId("replicated1"),
        CU.cacheId("replicated2"),
        CU.cacheId("replicated3"),
        CU.cacheId("replicated4"),
    };

    /** */
    private static final int PARTS = 1024;

    /** */
    private static final int PAGES = 10240;

    /**
     *
     */
    public void testDistributions() {
        printPageIdDistribution(
            CU.cacheId("partitioned"), 1024, 30_000, 32, 2.5f);

        printPageIdDistribution(
            CU.cacheId("partitioned"), 1024, 30_000, 64, 2.5f);

        printPageIdDistribution(
            CU.cacheId(null), 1024, 30_000, 32, 2.5f);
    }

    /**
     * @param cacheId Cache id.
     * @param parts Parts.
     * @param pagesPerPartition Pages per partition.
     * @param segments Segments.
     * @param capFactor Capacity factor.
     */
    private void printPageIdDistribution(
        int cacheId,
        int parts,
        int pagesPerPartition,
        int segments,
        float capFactor
    ) {
        int allIds = parts * pagesPerPartition;

        int perSegmentSize = allIds / segments;
        int capacity = (int)(perSegmentSize * capFactor);

        info("Total ids: " + allIds);

        List<Map<Integer, Integer>> collisionsPerSegment = new ArrayList<>(segments);

        for (int i = 0; i < segments; i++)
            collisionsPerSegment.add(new HashMap<Integer, Integer>(allIds / segments, 1.0f));

        int[] numInSegment = new int[segments];

        for (int p = 0; p < parts; p++) {
            for (int i = 0; i < pagesPerPartition; i++) {
                long pageId = PageIdUtils.pageId(p, (byte)0, i);

                int segment = PageMemoryImpl.segmentIndex(cacheId, pageId, segments);

                int idxInSegment = U.safeAbs(FullPageId.hashCode(cacheId, pageId)) % capacity;

                Map<Integer, Integer> idxCollisions = collisionsPerSegment.get(segment);

                Integer old = idxCollisions.get(idxInSegment);
                idxCollisions.put(idxInSegment, old == null ? 1 : old + 1);

                numInSegment[segment]++;
            }
        }

        for (int i = 0; i < collisionsPerSegment.size(); i++) {
            Map<Integer, Integer> idxCollisions = collisionsPerSegment.get(i);

            int distinctPositions = idxCollisions.size();

            int totalCnt = 0;
            int nonZero = 0;

            for (Map.Entry<Integer, Integer> collision : idxCollisions.entrySet()) {
                if (collision.getValue() != null) {
                    totalCnt += collision.getValue();
                    nonZero++;
                }
            }

            info(String.format("Segment stats [i=%d, total=%d, distinct=%d, spaceUsed=%d%%, avgItCnt=%.1f + ']",
                i, numInSegment[i], distinctPositions, distinctPositions * 100 / numInSegment[i],
                (float)totalCnt / nonZero));
        }

        info("==========================================================");
    }

    /**
     * Uncomment and run this test manually to get data to plot histogram for per-element distance from ideal.
     * You can use Octave to plot the histogram:
     * <pre>
     *     all = csvread("histo.txt");
     *     hist(all, 200)
     * </pre>
     *
     * @throws Exception If failed.
     */
    public void _testRealHistory() throws Exception {
        int cap = CACHE_IDS.length * PARTS * PAGES;

        info("Capacity: " + cap);

        long mem = FullPageIdTable.requiredMemory(cap);

        info(U.readableSize(mem, true));

        UnsafeMemoryProvider prov = new UnsafeMemoryProvider(new long[] {mem});
        prov.start();

        try {
            long seed = U.currentTimeMillis();

            info("Seed: " + seed + "L; //");

            Random rnd = new Random(seed);

            DirectMemoryRegion region = prov.memory().regions().get(0);

            FullPageIdTable tbl = new FullPageIdTable(region.address(), region.size(), true);

            Map<T2<Integer, Integer>, Integer> allocated = new HashMap<>();

            for (int i = 0; i < cap; i++) {
                int cacheId = CACHE_IDS[rnd.nextInt(CACHE_IDS.length)];
                int partId = rnd.nextInt(PARTS);

                T2<Integer, Integer> key = new T2<>(cacheId, partId);

                Integer pageIdx = allocated.get(key);

                pageIdx = pageIdx == null ? 1 : pageIdx + 1;

                if (pageIdx > PAGES)
                    continue;

                tbl.put(cacheId, PageIdUtils.pageId(partId, (byte)0, pageIdx), 1, 0);

                allocated.put(key, pageIdx);

                if (i > 0 && i % 100_000 == 0)
                    info("Done: " + i);
            }

            int[] scans = new int[cap];

            int cur = 0;

            for (T2<Integer, Integer> key : allocated.keySet()) {
                Integer alloc = allocated.get(key);

                if (alloc != null) {
                    for (int idx = 1; idx <= alloc; idx++) {
                        scans[cur] = tbl.distanceFromIdeal(key.get1(), PageIdUtils.pageId(key.get2(), (byte)0, idx), 0);

                        assert scans[cur] != -1;

                        cur++;
                    }
                }
            }

            try (FileOutputStream out = new FileOutputStream("histo.txt")) {
                PrintWriter w = new PrintWriter(new OutputStreamWriter(out));

                for (int scan : scans) {
                    if (scan != 0)
                        w.println(scan);
                }

                w.flush();
            }
        }
        finally {
            prov.stop();
        }
    }
}
