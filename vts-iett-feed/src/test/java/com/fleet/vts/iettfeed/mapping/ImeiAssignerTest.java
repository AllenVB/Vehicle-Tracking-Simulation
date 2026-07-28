package com.fleet.vts.iettfeed.mapping;

import com.fleet.vts.iettfeed.config.IettProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Stable per-bus assignment and hard capacity limit. */
class ImeiAssignerTest {

    private ImeiAssigner assignerWithCapacity(int cap) {
        IettProperties props = new IettProperties();
        props.setMaxVehicles(cap);
        return new ImeiAssigner(props);
    }

    @Test
    void assignsSequentialPaddedImeis() {
        ImeiAssigner assigner = assignerWithCapacity(100);

        assertThat(assigner.assign("C-290")).isEqualTo("000000000000001");
        assertThat(assigner.assign("C-228")).isEqualTo("000000000000002");
    }

    @Test
    void sameBusAlwaysGetsSameImei() {
        ImeiAssigner assigner = assignerWithCapacity(100);

        String first = assigner.assign("C-290");
        assigner.assign("C-228");
        assertThat(assigner.assign("C-290")).isEqualTo(first);
        assertThat(assigner.size()).isEqualTo(2);
    }

    @Test
    void returnsNullOnceCapacityExhausted() {
        ImeiAssigner assigner = assignerWithCapacity(2);

        assertThat(assigner.assign("A")).isEqualTo("000000000000001");
        assertThat(assigner.assign("B")).isEqualTo("000000000000002");
        assertThat(assigner.assign("C")).isNull();
        // an already-assigned bus still resolves after capacity is hit
        assertThat(assigner.assign("A")).isEqualTo("000000000000001");
    }
}
