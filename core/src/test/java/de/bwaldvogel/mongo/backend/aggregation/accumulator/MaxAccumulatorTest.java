package de.bwaldvogel.mongo.backend.aggregation.accumulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.Test;

public class MaxAccumulatorTest {

    private ComparingAccumulator accumulator = new MaxAccumulator(null, null);

    @Test
    public void testAccumulateNumbers() throws Exception {
        accumulator.aggregate(1);
        accumulator.aggregate(5);
        accumulator.aggregate(3);

        Object result = accumulator.getResult();
        assertThat(result).isEqualTo(5);
    }

    @Test
    public void testAccumulateArrays() throws Exception {
        accumulator.aggregate(Arrays.asList(10, 20, 30));
        accumulator.aggregate(Arrays.asList(3, 40));
        accumulator.aggregate(Arrays.asList(11, 25));

        Object result = accumulator.getResult();
        assertThat(result).isEqualTo(Arrays.asList(11, 25));
    }

    @Test
    public void testAccumulateArraysAndNonArray() throws Exception {
        accumulator.aggregate(Arrays.asList(3, 40));
        accumulator.aggregate(Arrays.asList(10, 20, 30));
        accumulator.aggregate(50);

        Object result = accumulator.getResult();
        assertThat(result).isEqualTo(Arrays.asList(10, 20, 30));
    }

    @Test
    public void testAccumulateNothing() throws Exception {
        Object result = accumulator.getResult();
        assertThat(result).isNull();
    }

}
