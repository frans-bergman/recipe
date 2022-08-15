package recipe.lang.ltol;

import recipe.lang.expressions.TypedVariable;
import recipe.lang.types.Boolean;
import recipe.lang.utils.Triple;

import java.util.HashMap;
import java.util.Map;

public class Necessary extends LTOL{
    Observation obs;
    LTOL value;

    public Necessary(Observation obs, LTOL value) {
        this.obs = obs;
        this.value = value;
    }

    public Observation getObservation() {
        return obs;
    }

    public LTOL getValue() {
        return value;
    }

    public String toString() {
        return "<" + obs + ">(" + value + ")";
    }

    public boolean isPureLTL() {
        return false;
    }

    public Triple<java.lang.Integer, Map<String, Observation>, LTOL> abstractOutObservations(java.lang.Integer counter) {
        Observation oldObs = new Observation(obs.observation);
        Map map = new HashMap<String, Observation>();
        map.put("obs" + counter, new Observation(oldObs.observation));

        TypedVariable var = new TypedVariable(Boolean.getType(), "obs" + counter);
//        obs = new Observation(var);

        return new Triple<>(counter + 1, map, new Next(new And(new Atom(var), this.value)));
    }
}
