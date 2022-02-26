package com.flippingutilities.utilities;

import lombok.Data;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Data
public class PotionGroup {
    String name;
    List<PotionDose> doses;

    public Set<Integer> getIds() {
        return doses.stream().map(d -> d.id).collect(Collectors.toSet());
    }

}