package recipe.analysis;

import recipe.Config;
import recipe.lang.System;
import recipe.lang.agents.*;
import recipe.lang.expressions.Predicate;
import recipe.lang.expressions.predicate.*;
import recipe.lang.types.BoundedInteger;
import recipe.lang.utils.exceptions.*;
import recipe.lang.expressions.Expression;
import recipe.lang.expressions.TypedValue;
import recipe.lang.expressions.TypedVariable;
import recipe.lang.ltol.LTOL;
import recipe.lang.ltol.Observation;
import recipe.lang.process.ReceiveProcess;
import recipe.lang.process.SendProcess;
import recipe.lang.types.Boolean;
import recipe.lang.types.Enum;
import recipe.lang.types.Type;
import recipe.lang.utils.Pair;
import recipe.lang.utils.Triple;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static recipe.Config.commVariableReferences;
import static recipe.Config.isCvRef;

public class ToNuXmv {

    public static Expression<Boolean> specialiseObservationToSendTransition(Map<String, Type> cvs,
                                                                            Expression<Boolean> obs,
                                                                            Expression<Boolean> sendGuard,
                                                                            Map<String, Expression> message,
                                                                            TypedValue sender,
                                                                            Expression channel) throws Exception {
        //handle message and sender variables
        Expression<Boolean> observation = obs.relabel((v) -> message.containsKey(v.getName()) ? message.get(v.getName()) : v)
                                                .relabel((v) -> v.getName().equals("sender") ? sender : v)
                                                .relabel((v) -> v.getName().equals(Config.channelLabel) ? channel : v);
        observation = observation.simplify();
        return handleCVsInObservation(cvs, observation, sendGuard).simplify();
    }

    public static Set<Expression<Boolean>> specialiseOnAllPossibleValues(String cv,
                                                                         Type type,
                                                                         Expression<Boolean> cond
                                                                    ) throws InfiniteValueTypeException,
                                                                            RelabellingTypeException,
                                                                            MismatchingTypeException,
                                                                            AttributeTypeException,
                                                                            TypeCreationException,
                                                                            AttributeNotInStoreException {
        //associate cv with all its possible values
        //instantiate condition for each value

        //get all cvs and order them
        List<TypedValue> vals = new ArrayList<>();
        if(type.getClass().equals(Boolean.class)){
            vals.add(Condition.getTrue());
            vals.add(Condition.getFalse());
        } else if(type.getClass().equals(BoundedInteger.class)){
            vals.addAll(((BoundedInteger) type).getAllValues());
        } else if(type.getClass().equals(Enum.class)){
            vals.addAll(((Enum) type).getAllValues());
        } else {
            throw new InfiniteValueTypeException("For LTOL analysis communication variables must be of finite type.");
        }

        Set<Expression<Boolean>> conditions = new HashSet<>();
        final String cv_cleaned = cv.replaceAll("^@", "");
        for(int j = 0; j < vals.size(); j++){
            TypedValue val = vals.get(j);
            conditions.add(cond.relabel((v) -> {
                String v_cleaned = v.getName().replaceAll("^@", "");
                if(v_cleaned.equals(cv_cleaned)){
                    return val;
                } else{
                    return v;
                }
            }).simplify());
        }

        return conditions;
    }

    public static Expression<Boolean> handleCVsInObservation(Map<String, Type> cvs, Expression<Boolean> obs, Expression<Boolean> sendGuard) throws Exception {
        if(obs.getClass().equals(And.class)){
            And obss = (And) obs;
            return new And(handleCVsInObservation(cvs, obss.getLhs(), sendGuard), handleCVsInObservation(cvs, obss.getRhs(), sendGuard));
        } else if(obs.getClass().equals(Or.class)){
            Or obss = (Or) obs;
            return new Or(handleCVsInObservation(cvs, obss.getLhs(), sendGuard), handleCVsInObservation(cvs, obss.getRhs(), sendGuard));
        } else if(obs.getClass().equals(Not.class)){
            Not obss = (Not) obs;
            return new Not(handleCVsInObservation(cvs, obss, sendGuard));
        } else if(obs.getClass().equals(Implies.class)){
            Implies obss = (Implies) obs;
            return new Implies(handleCVsInObservation(cvs, obss.getLhs(), sendGuard), handleCVsInObservation(cvs, obss.getRhs(), sendGuard));
        } else if(obs.getClass().equals(IsEqualTo.class)){
            IsEqualTo obss = (IsEqualTo) obs;
            if(obss.getLhs().getType().equals(Boolean.getType())){
                return new And(
                        new Implies(handleCVsInObservation(cvs, obss.getLhs(), sendGuard), handleCVsInObservation(cvs, obss.getRhs(), sendGuard)),
                        new Implies(handleCVsInObservation(cvs, obss.getRhs(), sendGuard), handleCVsInObservation(cvs, obss.getLhs(), sendGuard)));
            } else {
                return obs;
            }
        } else if(obs.getClass().equals(Predicate.class)){
            Predicate obss = (Predicate) obs;
            //t(forall(o)) = /\cv g_s -> o

            if(obss.getName().equals("forall")){
                Expression<Boolean> finalExpression = null;

                Set<Expression<Boolean>> current = new HashSet<>();
                current.add(new Implies(sendGuard, obss.getInput()));

                for(Map.Entry<String, Type> entry : cvs.entrySet()){
                    Set<Expression<Boolean>> next = new HashSet<>();
                    for(Expression<Boolean> expr : current){
                        Set<Expression<Boolean>> nextExpressions = specialiseOnAllPossibleValues(entry.getKey(), entry.getValue(), expr);
                        if(nextExpressions.contains(Condition.getFalse())){
                            next.clear();
                            next.add(Condition.getFalse());
                            break;
                        }
                        nextExpressions.removeIf((p) -> p.equals(Condition.getTrue()));
                        next.addAll(nextExpressions);
                    }
                    current = next;
                    if(current.size() == 1 && current.contains(Condition.getFalse())){
                        break;
                    }
                }

                for(Expression<Boolean> expr : current){
                    if(finalExpression == null){
                        finalExpression = expr.simplify();
                    } else{
                        finalExpression = new And(finalExpression, expr).simplify();
                    }
                }
                return finalExpression;
            } else if(obss.getName().equals("exists")) {
                //t(exists(o)) = \/cv g_s && o
                Expression<Boolean> t = null;

                Set<Expression<Boolean>> current = new HashSet<>();
                current.add(new And(sendGuard, obss.getInput()));

                // TODO restrict to cvs that appear in obss
                for(Map.Entry<String, Type> nameTypePair : cvs.entrySet()){
                    Set<Expression<Boolean>> next = new HashSet<>();
                    for(Expression<Boolean> expr : current){
                        Set<Expression<Boolean>> nextExpressions = specialiseOnAllPossibleValues(nameTypePair.getKey(), nameTypePair.getValue(), expr);
                        if(nextExpressions.contains(Condition.getTrue())){
                            next.clear();
                            next.add(Condition.getTrue());
                            break;
                        }
                        nextExpressions.removeIf((p) -> p.equals(Condition.getFalse()));
                        next.addAll(nextExpressions);
                    }
                    current = next;
                    if(current.size() == 1 && current.contains(Condition.getTrue())){
                        break;
                    }
                }

                for(Expression<Boolean> expr : current){
                    if(t == null){
                        t = expr.simplify();
                    } else{
                        t = new Or(t, expr).simplify();
                    }
                }
                return t;
            } else{
                throw new Exception("Predicate " + obss.getName() + " unknown.");
            }
        } else{
            return obs;
        }
    }

    public static Pair<List<LTOL>, Map<String, Observation>> ltolToLTLAndObservationVariables(List<LTOL> specs) throws RelabellingTypeException, InfiniteValueTypeException, MismatchingTypeException {
        Integer counter = 0;
        List<LTOL> pureLTLSpecs = new ArrayList<>();
        Map<String, Observation> observations = new HashMap<>();

        for(int i = 0; i < specs.size(); i++){
            Triple<Integer, Map<String, Observation>, LTOL> integerMapLTOLTriple = specs.get(i).abstractOutObservations(counter);
            counter = integerMapLTOLTriple.getLeft();
            observations.putAll(integerMapLTOLTriple.getMiddle());
            pureLTLSpecs.add(integerMapLTOLTriple.getRight());
        }

        return new Pair<>(pureLTLSpecs, observations);
    }

    public static String nuxmvModelChecking(System system) throws Exception {
        BufferedWriter writer = new BufferedWriter(new FileWriter("translation.smv"));
        String script = transform(system);
        writer.write(script);
        writer.close();
        Runtime rt = Runtime.getRuntime();
        Process pr = rt.exec(Config.getNuxmvPath() + " translation.smv");

        AtomicReference<String> out = new AtomicReference<>("");

        new Thread(() -> {
            BufferedReader input = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            String line = null;

            try {
                while ((line = input.readLine()) != null) {
                    if(!line.startsWith("***") && !line.trim().equals(""))
                        out.set(out.get() + line + "\n");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        pr.waitFor();

        return out.get();
    }

    public static String nuxmvTypeOfTypedVar(TypedVariable typedVariable){
        Type type = typedVariable.getType();
        if(type.getClass().equals(Enum.class)){
            return "{" + String.join(",", ((Enum) type).getValues()) + "}";
        } else{
            return type.name();
        }
    }

    public static String indent(String text){
        String s = text.replaceAll("(?<=(^|\n))", "    ");
        return s;
    }

    public static String transform(System system) throws Exception {
        return transform(system, false);
    }

    //TODO use sendTagsAsVars
    public static String transform(System system, boolean sendTagsAsVars) throws Exception {
        GuardReference.resolve = true;

        String nuxmv = "MODULE main\n";
        String vars = "VAR\n";
        String broadcastChannel = "broadcast";

        String define = "DEFINE\n";
        String init = "INIT\n";
        String trans = "";

        Pair<List<LTOL>, Map<String, Observation>> specsAndObs = ltolToLTLAndObservationVariables(system.getSpecs());
        List<LTOL> specs = specsAndObs.getLeft();
        Map<String, Observation> observations = specsAndObs.getRight();

        for(Map.Entry<String, Observation> entry : observations.entrySet()){
            vars += "\t" + entry.getKey() + " : boolean;\n";
        }

        List<AgentInstance> agentInstances = system.getAgentInstances();
        Map<String, List<String>> agentSendPreds = new HashMap<>();
        Map<String, List<String>> agentSendProgressConds = new HashMap<>();

        Set<String> sendProcessNames = new HashSet<>();
        Set<String> receiveProcessNames = new HashSet<>();
        List<String> keepFunctions = new ArrayList<>();

        String keepAll = "keep-all := TRUE";

        for(String sendName : sendProcessNames){
            String keep = "keep-not-" + sendName + " := TRUE";
            for(String sendName2 : sendProcessNames){
                if(!sendName.equals(sendName2)){
                    keep += " & next(" + sendName2 + ") = " + sendName2;
                }
            }

            keepFunctions.add(keep);
        }

        for(int i = 0; i < agentInstances.size(); i++) {
            Agent agenti = agentInstances.get(i).getAgent();
            String namei = agentInstances.get(i).getLabel();

            String keepThis = "keep-all-" + namei + " := TRUE";

            for(String var : agenti.getStore().getAttributes().keySet()){
                keepThis += " & next(" + namei + "-" + var + ") = " + namei + "-" + var;
                keepThis += " & next(" + namei + "-state) = " + namei + "-state";
            }

            String falsifyAllLabels = "falsify-not-" + namei + " := TRUE";

            for(Transition t : agenti.getReceiveTransitions()){
                String label = ((ReceiveProcess) t.getLabel()).getLabel();
                if (label != null && !label.equals("")){
                    keepThis += " & next(" + namei + "-" + label + ") = FALSE";
                    falsifyAllLabels += " & next(" + namei + "-" + label + ") = FALSE";
                    keepAll += " & next(" + namei + "-" + label + ") = FALSE";
                    receiveProcessNames.add(namei + "-" + label);

                    String falsifyAllLabelsExceptThis = "falsify-not-" + namei + "-" + label + " := TRUE";
                    for(Transition tt : agenti.getReceiveTransitions()){
                        String receiveLabel = ((ReceiveProcess) tt.getLabel()).getLabel();
                        if(receiveLabel != null && !receiveLabel.equals(label) &&!receiveLabel.equals("")){
                            falsifyAllLabelsExceptThis += " & next(" + namei + "-" + receiveLabel + ") = FALSE";
                        }
                    }
                    keepFunctions.add(falsifyAllLabelsExceptThis);
                }
            }

            keepFunctions.add(falsifyAllLabels);

            keepAll += " & keep-all-" + namei;

            keepFunctions.add(keepThis);
        }

        if(keepFunctions.size() > 0)
            define += "\t" + String.join(";\n\t", keepFunctions) + ";\n";
        define += "\t" + keepAll + ";\n";


        List<String> progress = new ArrayList<>();

        Map<Agent, Map<State, Set<ProcessTransition>>> agentStateSendTransitionMap = new HashMap<>();
        Map<Agent, Map<State, Set<ProcessTransition>>> agentStateReceiveTransitionMap = new HashMap<>();

        for(Agent agent : system.getAgents()){
            agentStateSendTransitionMap.put(agent, agent.getStateTransitionMap(agent.getSendTransitions()));
            agentStateReceiveTransitionMap.put(agent, agent.getStateTransitionMap(agent.getReceiveTransitions()));
        }


        for(int i = 0; i < agentInstances.size(); i++) {
            AgentInstance sendingAgentInstance = agentInstances.get(i);
            Agent sendingAgent = sendingAgentInstance.getAgent();
            String sendingAgentName = sendingAgentInstance.getLabel();
            TypedValue sendingAgentNameValue = new TypedValue(Config.getAgentType(), sendingAgentName);

            // Declare sendingAgent's states as nuxmv variables
            Stream<String> allStates = sendingAgent.getStates().stream().map(s -> s.toString());
            String stateList = String.join(",", allStates.toArray(String[]::new));

            for (TypedVariable typedVariable : sendingAgent.getStore().getAttributes().values()) {
                vars += "\t" + sendingAgentName + "-" + typedVariable.getName() + " : " + nuxmvTypeOfTypedVar(typedVariable) + ";\n";
            }

            vars += "\t" + sendingAgentName + "-state" + " : {" + stateList + "};\n";
            ///////////////

            // Initialise sendingAgent's states and init condition
            if(!init.equals("INIT\n")){
                init += "\t& ";
            }

            init += sendingAgentName + "-state" + " = " + sendingAgent.getInitialState().toString() + "\n";

            init += "\t& " + sendingAgent.getInit().relabel(v -> ((TypedVariable) v).sameTypeWithName(sendingAgentName + "-" + v)) + "\n";
            init += "\t& " + sendingAgentInstance.getInit().relabel(v -> ((TypedVariable) v).sameTypeWithName(sendingAgentName + "-" + v)) + "\n";
            ///////////////


            //For each state of the sending agents we are going to iterate over all of its possible send transitions
            // We shall create predicates for each send transition, and then disjunct them.
            for (State state : sendingAgent.getStates()) {
                Set<ProcessTransition> sendTransitions = agentStateSendTransitionMap.get(sendingAgent).get(state);

                String sendStateIsCurrentState = sendingAgentName + "-state" + " = " + state;


                if (sendTransitions != null && sendTransitions.size() > 0) {
                    List<String> transitionSendPreds = new ArrayList<>();
                    List<String> transitionSendProgressCond = new ArrayList<>();

                    for (ProcessTransition t : sendTransitions) {
                        //conditions for activation in now (is in source, and local guard holds),
                        // and effects in next (update, next state, and other stuff for receipt)
                        List<String> sendTriggeredIf = new ArrayList<>();
                        List<String> sendEffects = new ArrayList<>();

                        SendProcess sendingProcess = (SendProcess) t.getLabel();

                        Expression<Enum> sendingOnThisChannelVarOrVal = sendingProcess
                                .getChannel()
                                .relabel(v -> v.sameTypeWithName(sendingAgentName + "-" + v));

                        // add the guard of the sendingProcess to the guards required for the send to trigger
                        sendTriggeredIf.add(sendingProcess.getPsi()
                                .relabel(v -> v.sameTypeWithName(sendingAgentName + "-" + v)).simplify().toString());


                        // add next state to send effects
                        sendEffects.add("next(" + sendingAgentName + "-state" + ") = " + t.getDestination());

                        // Add updates to send effects
                        for (Map.Entry<String, Expression> entry : sendingProcess.getUpdate().entrySet()) {
                            sendEffects.add(
                                    "next(" + sendingAgentName + "-" + entry.getKey() + ") " +
                                    "= (" + entry.getValue().relabel(v -> ((TypedVariable) v).sameTypeWithName(sendingAgentName + "-" + v)).simplify() + ")");
                        }
                        //keep variable values not mentioned in the update
                        for (String var : sendingAgent.getStore().getAttributes().keySet()) {
                            if (!sendingProcess.getUpdate().containsKey(var)) {
                                sendEffects.add("next(" + sendingAgentName + "-" + var + ") = " + sendingAgentName + "-" + var);
                            }
                        }

                        // relabelling message var names
                        Map<String, Expression> relabelledMessage = new HashMap<>();
                        for (Map.Entry<String, Expression> entry : sendingProcess.getMessage().entrySet()) {
                            relabelledMessage.put(entry.getKey(), entry.getValue().relabel(v -> ((TypedVariable) v).sameTypeWithName(sendingAgentName + "-" + ((TypedVariable) v).getName())).simplify());
                        }

                        //relabelling send agent variables in sendGuard
                        Expression<Boolean> sendGuardExpr = sendingProcess.getMessageGuard().relabel(v -> {
                            //if v is just the special variable we use in our syntax to refer to the current
                            // channel being sent on, then replace it with the sending transitions channel reference
                            if (v.getName().equals(Config.channelLabel)) {
                                return sendingOnThisChannelVarOrVal;
                            } else {
                                //relabelling local variables to those of the sending agents
                                return isCvRef(system, v.getName())
                                        ? v
                                        : v.sameTypeWithName(sendingAgentName + "-" + v);
                            }
                        }).simplify();

                        //Dealing with LTOL observations
                        for(Map.Entry<String, Observation> entry : observations.entrySet()){
                            Observation obs = entry.getValue();
                            String var = entry.getKey();

                            Expression<Boolean> observationCondition = specialiseObservationToSendTransition(
                                    commVariableReferences(system.getCommunicationVariables()),
                                    obs.getObservation(),
                                    sendGuardExpr,
                                    relabelledMessage,
                                    sendingAgentNameValue,
                                    sendingOnThisChannelVarOrVal);

                            sendEffects.add("next(" + var + ") = (" + observationCondition + ")");
                        }

                        //Now we will iterate over all other agents, and for every receive transition,
                        // we create predicates for when the above send transition can trigger the receive transition.
                        List<String> agentReceivePreds = new ArrayList<>();
                        List<String> agentReceiveProgressConds = new ArrayList<>();

                        agentInstanceReceiveLoop:
                        for (int j = 0; j < agentInstances.size(); j++) {
                            if (i != j) {
                                AgentInstance receivingAgentInstance = agentInstances.get(j);
                                Agent receiveAgent = receivingAgentInstance.getAgent();
                                String receiveName = receivingAgentInstance.getLabel();

                                Expression<Boolean> receiveGuardExpr = receiveAgent.getReceiveGuard()
                                        .relabel(v -> v.toString().equals(Config.channelLabel)
                                                ? sendingOnThisChannelVarOrVal
                                                : v.sameTypeWithName(receiveName + "-" + v));
                                receiveGuardExpr = receiveGuardExpr.simplify();

                                String receiveGuard;

                                if (sendingOnThisChannelVarOrVal.toString().equals(Config.broadcast))
                                    //then sending channel is broadcast and we always want to listen to broadcasts
                                    receiveGuard = "TRUE";
                                else if (receiveGuardExpr.toString().equals("FALSE")) {
                                    receiveGuard = "FALSE";
                                } else if(sendingOnThisChannelVarOrVal.getClass().equals(TypedValue.class)){
                                    receiveGuard = "(" + receiveGuardExpr + ")";
                                } else {
                                    receiveGuard = "(" + receiveGuardExpr + ") | " + sendingOnThisChannelVarOrVal + " = " + broadcastChannel;
                                }

                                //relabelling sendGuard
                                // remove @s
                                Expression<Boolean> sendGuardExprHere = sendGuardExpr.relabel(v -> {
                                    return v.getName().startsWith("@") ? ((TypedVariable) v).sameTypeWithName(v.getName().substring(1)) : v;
                                });

                                // rename references to cvs to receiving agents cv
                                sendGuardExprHere = sendGuardExprHere.relabel(v -> {
                                    //if v is just the special variable we use in our syntax to refer to the current
                                    // channel being sent on, then replace it with the sending transitions channel reference
                                    try {
                                        //relabelling cvs to those of the receiving agents

                                        return isCvRef(system, v.getName())
                                                ? receiveAgent.getRelabel().get(v).relabel(vv -> ((TypedVariable) vv).sameTypeWithName(receiveName + "-" + vv))
                                                : v;
                                    } catch (RelabellingTypeException | MismatchingTypeException e) {
                                        e.printStackTrace();
                                    }
                                    return null;
                                }).simplify();


                                Map<String, List<String>> receiveAgentReceivePreds = new HashMap<>();
                                Map<String, List<String>> receiveAgentReceiveProgressConds = new HashMap<>();
                                for (State receiveAgentState : receiveAgent.getStates()) {
                                    Set<ProcessTransition> receiveAgentReceiveTransitions = agentStateReceiveTransitionMap.get(receiveAgent).get(receiveAgentState);
                                    if(receiveAgentReceiveTransitions == null){
                                        receiveAgentReceiveTransitions = new HashSet<>();
                                    }

                                    String receiveStateIsCurrentState = receiveName + "-state" + " = " + receiveAgentState.toString();

                                    List<String> transitionReceivePreds = new ArrayList<>();
                                    List<String> transitionReceiveProgressConds = new ArrayList<>();

                                    receiveTransLoop:
                                    for (Transition receiveTrans : receiveAgentReceiveTransitions) {
                                        ReceiveProcess receiveProcess = (ReceiveProcess) receiveTrans.getLabel();

                                        List<String> receiveTransTriggeredIf = new ArrayList<>();
                                        List<String> receiveTransEffects = new ArrayList<>();

                                        //This is a hack to allow us to stop considering this transition
                                        // when the incoming message does not contain all message vars required
                                        // for this transition
                                        AtomicReference<java.lang.Boolean> stop = new AtomicReference<java.lang.Boolean>(false);
                                        Function<Expression, Expression> stopHelper = (e) -> {
                                            stop.set(true);
                                            return e;
                                        };

                                        // relabel the transition guard
                                        //     for message vars with the relabelled sending Agent messages and
                                        //     for local vars with the name of the agent
                                        Expression<recipe.lang.types.Boolean> receiveTransitionGuard = receiveProcess.getPsi()
                                                .relabel(v -> sendingProcess.getMessage().containsKey(((TypedVariable) v).getName())
                                                        ? relabelledMessage.get(((TypedVariable) v).getName())
                                                        : (system.getMessageStructure().containsKey(((TypedVariable) v).getName())
                                                        ? stopHelper.apply(v)
                                                        : ((TypedVariable) v).sameTypeWithName(receiveName + "-" + v)));
                                        receiveTransitionGuard = receiveTransitionGuard.simplify();

                                        ////stop considering this transition if the incoming message does not contain all message vars required
                                        if (stop.get()) continue receiveTransLoop;
                                        ////////////////////////

                                        // if the receiveTransitionGuard has evaluated to false, then we can stop.
                                        if (receiveTransitionGuard.equals(Condition.getFalse()))
                                            continue receiveTransLoop;
                                        else
                                            receiveTransTriggeredIf.add(receiveTransitionGuard.toString());


                                        Expression<Enum> receivingOnThisChannelVarOrVal = receiveProcess.getChannel()
                                                .relabel(v -> v.sameTypeWithName(receiveName + "-" + v.toString()));

                                        // if the sending and receiving transition channels are both values,
                                        // and they are not the same values then this transition can stop being
                                        // considered
                                        if (receivingOnThisChannelVarOrVal.getClass().equals(TypedValue.class)
                                                && sendingOnThisChannelVarOrVal.getClass().equals(TypedValue.class)
                                                && !sendingOnThisChannelVarOrVal.equals(receivingOnThisChannelVarOrVal))
                                            continue receiveTransLoop;
                                        else
                                            receiveTransTriggeredIf.add(sendingOnThisChannelVarOrVal + " = " + receivingOnThisChannelVarOrVal);
//                                            agentReceiveNows.add(receiveNow);

                                        //for each variable update, if the updates uses a message variable that is
                                        // not set by the send transition, then exit
                                        // else relabel variables appropriately
                                        for (Map.Entry<String, Expression> entry : receiveProcess.getUpdate().entrySet()) {
                                            receiveTransEffects
                                                    .add("next(" + receiveName + "-" + entry.getKey() + ") = "
                                                            + entry.getValue().relabel(v ->
                                                            sendingProcess.getMessage().containsKey(((TypedVariable) v).getName())
                                                                    ? relabelledMessage.get(((TypedVariable) v).getName())
                                                                    : (system.getMessageStructure().containsKey(((TypedVariable) v).getName())
                                                                    ? stopHelper.apply((TypedVariable) v)
                                                                    : ((TypedVariable) v).sameTypeWithName(receiveName + "-" + v))));
                                        }

                                        //stop considering this transition if update uses a message variable
                                        // that is not set by the send transition
                                        if (stop.get()) continue receiveTransLoop;
                                        ///////

                                        // keep the same variables for variables not mentioned in the update
                                        for (String var : receiveAgent.getStore().getAttributes().keySet()) {
                                            if (!receiveProcess.getUpdate().containsKey(var)) {
                                                receiveTransEffects.add("next(" + receiveName + "-" + var + ") = " + receiveName + "-" + var);
                                            }
                                        }

                                        //if the receive transition is labelled, then set it's next value as true
                                        // and set all other labels of this agents as false
                                        if (receiveProcess.getLabel() != null && !receiveProcess.getLabel().equals("")) {
                                            receiveTransEffects.add("next(" + receiveName + "-" + receiveProcess.getLabel() + ") = TRUE");
                                            receiveTransEffects.add("falsify-not-" + receiveName + "-" + receiveProcess.getLabel() + "");
                                        } else {
                                            // if the transition is not labelled, then when it is taken no label
                                            // should be set as true
                                            receiveTransEffects.add("falsify-not-" + receiveName);
                                        }

                                        // set the transition destination state as the next state
                                        receiveTransEffects.add("next(" + receiveName + "-state" + ") = " + receiveTrans.getDestination());

                                        transitionReceivePreds.add("(" + String.join(")\n \t\t& (", receiveTransTriggeredIf) + ") & "
                                                + "(" + String.join(")\n \t\t& (", receiveTransEffects) + ")");

                                        transitionReceiveProgressConds.add("(" + String.join(")\n \t\t& (", receiveTransTriggeredIf) + ")");
                                    }

                                    if(transitionReceivePreds.size() > 0) {
                                        receiveAgentReceivePreds.put(receiveStateIsCurrentState, transitionReceivePreds);
                                        receiveAgentReceiveProgressConds.put(receiveStateIsCurrentState, transitionReceiveProgressConds);
                                    }
                                }

                                List<String> currentAgentReceivePreds = new ArrayList<>();
                                List<String> currentAgentProgressConds = new ArrayList<>();


                                for(State explicitState : receiveAgent.getStates()) {
                                    String stateCond = receiveName + "-state = " + explicitState.toString();
                                    String noExplicitTransition = sendingOnThisChannelVarOrVal.toString() + " = " + broadcastChannel;
                                    if (receiveAgentReceivePreds.containsKey(stateCond)
                                            && !(receiveAgentReceivePreds.get(stateCond) == null)){
                                        List<String> receiveTransPreds = receiveAgentReceivePreds.get(stateCond);
                                        List<String> receiveTransProgressConds = receiveAgentReceiveProgressConds.get(stateCond);
                                        if (receiveTransPreds.size() > 0) {
                                            currentAgentReceivePreds.add(stateCond + " & " + noExplicitTransition + " & keep-all-" + receiveName + " & !(" + String.join(" | ", receiveTransProgressConds) + ")");
                                            currentAgentProgressConds.add(stateCond + " & " + noExplicitTransition + " & !(" + String.join(" | ", receiveTransProgressConds) + ")");
                                        }
                                    }
                                    else {
                                        currentAgentReceivePreds.add(stateCond + " & " + noExplicitTransition + " & keep-all-" + receiveName);
                                        currentAgentProgressConds.add(stateCond + " & " + noExplicitTransition);
                                    }
                                }

                                //TRANSITION SEMANTICS

                                //This checks that the receive guard holds, the transition relation holds, and the send guard holds
                                // otherwise a disjunct is not added to the above lists
                                if (receiveAgentReceivePreds.size() > 0
                                        && !receiveGuard.equals("FALSE")
                                        && !sendGuardExprHere.toString().equals("FALSE")) {
                                    //Compute transition predicate (and progress predicate) for current agent
                                    List<String> stateTransitionPreds = new ArrayList<>();
                                    List<String> stateTransitionProgressConds = new ArrayList<>();

                                    for (Map.Entry<String, List<String>> entry : receiveAgentReceivePreds.entrySet()) {
                                        stateTransitionPreds.add(entry.getKey() + " & ("
                                                + String.join(")\n \t\t| (", entry.getValue()) + ")");
                                    }
                                    for (Map.Entry<String, List<String>> entry : receiveAgentReceiveProgressConds.entrySet()) {
                                        stateTransitionProgressConds.add(entry.getKey() + " & ("
                                                + String.join(")\n \t\t| (", entry.getValue()) + ")");
                                    }

                                    String transitionPred = "(" + String.join(")\n \t\t| (", stateTransitionPreds) + ")";
                                    String transitionProgressCond = "(" + String.join(")\n \t\t| (", stateTransitionProgressConds) + ")";

                                    if (receiveGuard.equals("TRUE") && sendGuardExprHere.toString().equals("TRUE")) {
                                        currentAgentReceivePreds.add(transitionPred);
                                        currentAgentProgressConds.add(transitionProgressCond);
                                    } else if (receiveGuard.equals("TRUE")) {
                                        currentAgentReceivePreds.add("(" + transitionPred + ")\n \t\t& (" + sendGuardExprHere + ")");
                                        currentAgentProgressConds.add("(" + transitionProgressCond + ")\n \t\t& (" + sendGuardExprHere + ")");
                                    } else if (sendGuardExprHere.toString().equals("TRUE")) {
                                        currentAgentReceivePreds.add("(" + receiveGuard + ")\n \t\t& (" + transitionPred + ")");
                                        currentAgentProgressConds.add("(" + receiveGuard + ")\n \t\t& (" + transitionProgressCond + ")");
                                    } else {
                                        currentAgentReceivePreds.add("(" + receiveGuard + ")\n \t\t& (" + transitionPred + ")\n \t\t& (" + sendGuardExprHere + ")");
                                        currentAgentProgressConds.add("(" + receiveGuard + ")\n \t\t& (" + transitionProgressCond + ")\n \t\t& (" + sendGuardExprHere + ")");
                                    }
                                } else if(receiveAgentReceivePreds.size() > 0){
                                    throw new Exception("receiveAgentReceivePreds is empty");
                                }

                                //if receiveguard is false then the whole predicate is true
                                if (receiveGuard.equals("FALSE")){
                                    currentAgentReceivePreds.clear();
                                    currentAgentReceivePreds.add("keep-all-" + receiveName);

                                    currentAgentProgressConds.clear();
                                    currentAgentProgressConds.add("TRUE");
                                }
                                // if it is true then this disjunct is false (so add nothing)
                                else if(receiveGuard.equals("TRUE")){
                                    //do nothing
                                }
                                // otherwise add the receiveguard
                                else{
                                    currentAgentReceivePreds.add("!(" + receiveGuard + ") & keep-all-" + receiveName);
                                    currentAgentProgressConds.add("!(" + receiveGuard + ")");
                                }

                                if(sendingOnThisChannelVarOrVal.getClass().equals(TypedValue.class)
                                        && sendingOnThisChannelVarOrVal.toString().equals(Config.broadcast)
                                        && sendGuardExprHere.toString().equals("FALSE")){
                                    currentAgentReceivePreds.clear();
                                    currentAgentReceivePreds.add("keep-all-" + receiveName);
                                    currentAgentProgressConds.clear();
                                    currentAgentProgressConds.add("TRUE");
                                } else if((sendingOnThisChannelVarOrVal.getClass().equals(TypedValue.class)
                                        && !sendingOnThisChannelVarOrVal.toString().equals(Config.broadcast))
                                        || sendGuardExprHere.toString().equals("TRUE")){
                                    //do nothing, disjunct does not hold
                                } else{
                                    if(sendingOnThisChannelVarOrVal.getClass().equals(TypedValue.class)
                                            && sendingOnThisChannelVarOrVal.toString().equals(Config.broadcast)){
                                        currentAgentReceivePreds.add("!(" + sendGuardExprHere.toString() + ") & " + "keep-all-" + receiveName);
                                        currentAgentProgressConds.add("!(" + sendGuardExprHere.toString() + ")");
                                    } else if(sendGuardExprHere.toString().equals("FALSE")){
                                        currentAgentReceivePreds.add(sendingOnThisChannelVarOrVal.toString() + " = " + broadcastChannel + " & " + "keep-all-" + receiveName);
                                        currentAgentProgressConds.add(sendingOnThisChannelVarOrVal.toString() + " = " + broadcastChannel);
                                    } else{
                                        currentAgentReceivePreds.add(sendingOnThisChannelVarOrVal.toString() + " = " + broadcastChannel + " & !(" + sendGuardExprHere.toString() + ") & " + "keep-all-" + receiveName);
                                        currentAgentProgressConds.add(sendingOnThisChannelVarOrVal.toString() + " = " + broadcastChannel + " & !(" + sendGuardExprHere.toString() + ")");
                                    }
                                }


                                if(currentAgentReceivePreds.size() == 0){
                                    throw new Exception("currentAgentReceivePreds is empty");
//                                    currentAgentReceivePreds.add("FALSE");
//                                    currentAgentProgressConds.add("FALSE");
                                }

                                agentReceivePreds.add("(" + String.join(")\n\n \t\t| (", currentAgentReceivePreds) + ")");
                                agentReceiveProgressConds.add("(" + String.join(")\n\n \t\t| (", currentAgentProgressConds) + ")");

                            }//end if(i != j)
                        }//for loop over agents for receive

                        String sendTriggeredIfPred = "(" + String.join(")\n \t\t& (", sendTriggeredIf) + ")";
                        String sendEffectsPred = "(" + String.join(")\n \t\t& (", sendEffects) + ")";

                        String agentSendPred = "(" + sendTriggeredIfPred + ")\n \t\t& (" + sendEffectsPred + ")";
                        String agentSendProgressCond = "(" + sendTriggeredIfPred + ")\n";
                        if(agentReceivePreds.size() > 0) {
                            agentSendPred += "\n \t\t& (" + String.join(")\n\n \t\t& (", agentReceivePreds) + ")";
                            agentSendProgressCond += "\n \t\t& (" + String.join(")\n\n \t\t& (", agentReceiveProgressConds) + ")";
                        }

                        if(sendingProcess.getLabel() != null && !sendingProcess.getLabel().equals("")) {
                            if((sendingAgentName + "-" + sendingProcess.getLabel()).equals("client2-sRelease")){
                                java.lang.System.out.println();
                            }
                            define +=  "\t" + sendingAgentName + "-" + sendingProcess.getLabel() + " := " + agentSendPred + ";\n";
                            transitionSendPreds.add(sendingAgentName + "-" + sendingProcess.getLabel());
                        } else {
                            transitionSendPreds.add(agentSendPred);
                        }

                        transitionSendProgressCond.add(agentSendProgressCond);
                    }
                    agentSendPreds.put(sendStateIsCurrentState, transitionSendPreds);
                    agentSendProgressConds.put(sendStateIsCurrentState, transitionSendProgressCond);
                }//endif
            }
        }

        if(agentSendPreds.size() > 0) {
            List<String> stateTransitionPreds = new ArrayList<>();
            List<String> stateTransitionProgressConds = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : agentSendPreds.entrySet()) {
                stateTransitionPreds.add(entry.getKey() + "\n\n \t\t& ((" + String.join(")\n\n \t\t| (", entry.getValue()) + "))");
            }
            for (Map.Entry<String, List<String>> entry : agentSendProgressConds.entrySet()) {
                stateTransitionProgressConds.add(entry.getKey() + "\n\n \t\t& ((" + String.join(")\n\n \t\t| (", entry.getValue()) + "))");
            }

            trans += "(" + String.join(")\n\n \t\t| (", stateTransitionPreds) + ");\n";

            progress = stateTransitionProgressConds;
        }
        else
            trans += "FALSE;\n";

        for(String name : receiveProcessNames){
            vars += "\t" + name + " : " + "boolean;\n";
        }
        nuxmv += vars;
        define += "\ttransition := " + trans;
        if(progress.size() > 0)
            define += "\tprogress := (" + String.join(")\n \t\t| (", progress) + ");\n";
        else
            define += "\tprogress := FALSE;\n";
        nuxmv += define;
        List<String> constants = new ArrayList<>();
        for(String label : Enum.getEnumLabels()){
            constants.addAll(Enum.getEnum(label).getValues());
        }
        if(constants.size() > 0)
            nuxmv += "CONSTANTS\n\t";
            nuxmv +=  String.join(", ", constants);
            nuxmv += ";\n";

//        for(String name : sendProcessNames){
//            init += "\t& " + name + " = " + "FALSE\n";
//        }

        for(String name : receiveProcessNames){
            init += "\t& " + name + " = " + "FALSE\n";
        }

        nuxmv += init;
        nuxmv += "TRANS\n";
        nuxmv += "\t(transition)\n \t\t| (!progress & keep-all)\n";
        nuxmv = nuxmv.replaceAll("&( |\n)*TRUE(( )*&( )*)( |\n)*", "");
        nuxmv = nuxmv.replaceAll("TRUE(( )*&( )*)", "");
//        nuxmv = nuxmv.replaceAll("TRUE\n(\t& )", "\t");
        nuxmv = nuxmv.replaceAll("==", " = ");
        nuxmv = nuxmv.replaceAll("\\*", broadcastChannel);


        nuxmv += String.join("\n", specs.stream().map((s) -> "LTLSPEC " + s.toString()).toArray(String[]::new));

        GuardReference.resolve = false;

        return nuxmv;
    }

}
