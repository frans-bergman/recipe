package recipe.lang;

import org.petitparser.parser.primitive.FailureParser;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.SettableParser;
import org.petitparser.parser.primitive.CharacterParser;
import org.petitparser.parser.primitive.StringParser;
import recipe.Config;
import recipe.lang.agents.Agent;
import recipe.lang.agents.AgentInstance;
import recipe.lang.utils.exceptions.ParsingException;
import recipe.lang.utils.exceptions.TypeCreationException;
import recipe.lang.expressions.TypedVariable;
import recipe.lang.ltol.LTOL;
import recipe.lang.types.Enum;
import recipe.lang.types.Guard;
import recipe.lang.types.Type;
import recipe.lang.utils.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static recipe.lang.utils.Parsing.*;
import static recipe.lang.utils.Parsing.typedVariableList;

public class System{
    Map<String, Type> messageStructure;
    Map<String, Type> communicationVariables;
    Map<String, Type> guardDefinitions;
    Set<Agent> agents;
    List<AgentInstance> agentsInstances;

    public List<LTOL> getSpecs() {
        return specs;
    }
    public void setSpecs(List<LTOL> specs) {
        this.specs = specs;
    }

    List<LTOL> specs;

    public Map<String, Type> getMessageStructure() {
        return messageStructure;
    }

    public Map<String, Type> getCommunicationVariables() {
        return communicationVariables;
    }

    public Set<Agent> getAgents() {
        return agents;
    }
    public List<AgentInstance> getAgentInstances() {
        return agentsInstances;
    }

    public System(Map<String, Type> messageStructure, Map<String, Type> communicationVariables,
                  Map<String, Type> guardDefinitions, Set<Agent> agents,
                  List<AgentInstance> agentsInstances, List<LTOL> specs) {
        this.messageStructure = messageStructure;
        this.communicationVariables = communicationVariables;
        this.guardDefinitions = guardDefinitions;
        this.agents = agents;
        this.agentsInstances = agentsInstances;
        this.specs = specs;
    }

    public static Parser parser(){
        Enum.clear();
        Guard.clear();

        SettableParser parser = SettableParser.undefined();

//        AtomicReference<TypingContext> channelContext = new AtomicReference<>(new TypingContext());
        AtomicReference<String> error = new AtomicReference<>("");
        AtomicReference<TypingContext> messageContext = new AtomicReference<>(new TypingContext());
        AtomicReference<TypingContext> communicationContext = new AtomicReference<>(new TypingContext());
        AtomicReference<TypingContext> guardDefinitionsContext = new AtomicReference<>(new TypingContext());
        AtomicReference<Set<Agent>> agents = new AtomicReference<>(new HashSet<>());

        parser.set((labelledParser("channels", channelValues())
                        .mapWithSideEffects((List<String> values) -> {
                            try {
                                if (values.contains(Config.broadcast)) {
                                    throw new ParsingException(Config.broadcast + " is a reserved keyword and defined implicit, there is no need to add it to declared channel values.");
                                }

                                List<String> valuesWithBroadcast = new ArrayList<>(values);
                                valuesWithBroadcast.add(Config.broadcast);
                                //do not remove, stored inside Enum
                                Enum channelEnum = new Enum(Config.channelLabel, valuesWithBroadcast);

                            } catch (TypeCreationException | ParsingException e) {
                                e.printStackTrace();
                            }
                            return values;
                        }).or(FailureParser.withMessage("Error in channels definition.")))
                        .seq(Parsing.enumDefinitionParser().star().or(FailureParser.withMessage("Error in enum definitions.")))
                        .seq(labelledParser("message-structure", typedVariableList())
                                .map((List<TypedVariable> values) -> {
                                    messageContext.get().setAll(new TypingContext(values));
                                    return values;
                                }).or(FailureParser.withMessage("Error in message-structure definition.")))
                        .seq(labelledParser("communication-variables", typedVariableList())
                                .map((List<TypedVariable> values) -> {
                                    communicationContext.get().setAll(new TypingContext(values));
                                    return values;
                                }).or(FailureParser.withMessage("Error in communication-variables definition.")))
                        .seq(new LazyParser<LazyTypingContext>((LazyTypingContext context) ->
                                guardDefinitionList(context.resolve()) //TODO may want to range over channel values and communication values in future
                                .map((Map<String, Type> values) -> {
                                    guardDefinitionsContext.get().setAll(new TypingContext(values));
                                    return values;
                                }), new LazyTypingContext(communicationContext)))//.or(StringParser.of("agent").trim().and().seq(FailureParser.withMessage("Error in guard definition."))))
                        .seq(StringParser.of("agent").trim().and().seq(new LazyParser<Pair<Pair<TypingContext, TypingContext>, TypingContext>>(
                                (Pair<Pair<TypingContext, TypingContext>, TypingContext> msgCmncGuardContext) ->
                                {
                                    try {
                                        Parser agent = Agent.parser(msgCmncGuardContext.getLeft().getLeft(),
                                                msgCmncGuardContext.getLeft().getRight(), msgCmncGuardContext.getRight()).plus();//.seq((StringParser.of("system").trim()).and());
                                        return agent;
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                    return null;
                                },
                                new Pair<>(new Pair<>(messageContext.get(), communicationContext.get()), guardDefinitionsContext.get())).trim().plus()
                                .map((List agentss) -> {
                                    agentss.stream().flatMap(x -> x instanceof Agent ? Stream.of(x) : ((List) x).stream()).forEach((y) -> agents.get().add((Agent) y));
                                    return agentss;
                                })
//
                        ).or(StringParser.of("agent").trim()
                                .seq((CharacterParser.word().star().seq(CharacterParser.whitespace()).flatten())
                                        .map((String val) -> {
                                            error.set(val);
                                            return val;
                                        }).seq(FailureParser.withMessage("Error in agent " + error.get() + " definition.")))))
                        .seq(labelledParser("system", "=", new LazyParser<Boolean>((Boolean b) -> {
                            return AgentInstance.parser(agents.get()).separatedBy(CharacterParser.of('|').trim());
                        }, true))
                                        .map((List<Object> values) -> {
                                            List<AgentInstance> agentInstances = new ArrayList<>();
                                            for (Object x : values) {
                                                if(x.getClass().equals(AgentInstance.class)){
                                                    agentInstances.add((AgentInstance) x);
                                                }
                                            }

                                            return agentInstances;
                                        }).or(StringParser.of("system").and().seq(FailureParser.withMessage("Error in system definition.")))
                        )
                        .seq(((StringParser.of("SPEC "))
                                .flatten()
                                .seq(CharacterParser.noneOf(";").plus()).flatten())
                             .delimitedBy((CharacterParser.of(';').or(CharacterParser.of('\n'))).trim())
                             .optional(new ArrayList<>()))
                        .map((List<Object> values) -> {
                            Set<String> channels = new HashSet<>((List<String>) values.get(0));
                            Map<String, Type> messageStructure = messageContext.get().getVarType();
                            Map<String, Type> communicationVariables = communicationContext.get().getVarType();
                            Map<String, Type> guardDefinitions = (Map<String, Type>) values.get(4);
                            List<AgentInstance> agentInstances = (List) values.get(6);

                            List<String> specsStrings = new ArrayList<>();
                            for(Object obj : (List<Object>) values.get(7)){
                                if(obj.getClass().equals(String.class)){
                                    String[] spec = ((String) obj).split("(?=SPEC)");
                                    specsStrings.addAll(List.of(spec));
                                }
                            }

                            specsStrings.removeIf(x -> x.trim().equals(""));
                            specsStrings.forEach(x -> x.trim());

                            System system = new System(messageStructure, communicationVariables, guardDefinitions, agents.get(), agentInstances, new ArrayList<>());

                            List<LTOL> ltolSpecs = new ArrayList<>();
                            try {
                                Parser ltolParser = LTOL.parser(system);

                                for(String spec : specsStrings){
                                    spec = spec.replaceAll("^SPEC", "").trim();
                                    java.lang.System.out.println(spec);
                                    LTOL ltolSpec = ltolParser.parse(spec).get();
                                    ltolSpecs.add(ltolSpec);
                                }
                                system.setSpecs(ltolSpecs);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }

                            return system;
                        })
        );

        return parser;
    }

    public List<String> toDOT(){
        List<String> dot = new ArrayList<String>();
        for(AgentInstance agentInstance : this.agentsInstances){
            Agent agent = agentInstance.getAgent();
            String digraph = "digraph \"" + agentInstance.getLabel() + "\"{\n" + agent.toDOT() + "\n}";
            digraph = digraph.replaceAll("\\\"", "\\\\\"");
            digraph = digraph.replaceAll("\n\t", " ");
            digraph = digraph.replaceAll("\n+", " ");
            digraph = "{\"name\" : \"" + agentInstance.getLabel() + "\", \"graph\" : \"" + digraph + "\"}";
            dot.add(digraph);
        }

        return dot;
    }

    public boolean isSymbolic(){
        for(AgentInstance agentInstance : agentsInstances){
            if(agentInstance.getAgent().isSymbolic()){
                return true;
            }
        }

        return false;
    }
}
