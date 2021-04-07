package recipe.lang.expressions.predicate;

import org.petitparser.parser.Parser;
import org.petitparser.parser.primitive.CharacterParser;
import recipe.lang.exception.AttributeNotInStoreException;
import recipe.lang.exception.AttributeTypeException;
import recipe.lang.exception.RelabellingTypeException;
import recipe.lang.expressions.Expression;
import recipe.lang.expressions.TypedVariable;
import recipe.lang.store.Store;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class Or extends Condition {

	private Condition lhs;
	private Condition rhs;

	public Or(Condition lhs, Condition rhs) {
		super(Condition.PredicateType.OR);
		if ((lhs == null) || (rhs == null)) {
			throw new NullPointerException();
		}
		this.lhs = lhs;
		this.rhs = rhs;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (this == obj) {
			return true;
		}
		if (super.equals(obj)) {
			Or p = (Or) obj;
			return lhs.equals(p.lhs) && rhs.equals(p.rhs);
		}
		return false;
	}

	@Override
	public String toString() {
		return "(" + lhs.toString() + ") | (" + rhs.toString() + ")";
	}

	public Expression getLhs() {
		return lhs;
	}

	public Expression getRhs() {
		return rhs;
	}

	@Override
	public BooleanValue valueIn(Store store) throws AttributeNotInStoreException, AttributeTypeException {
		Expression lhsObject = lhs.valueIn(store);
		Expression rhsObject = rhs.valueIn(store);
		if (lhsObject.equals(Condition.TRUE) || rhsObject.equals(Condition.TRUE)) {
			return Condition.TRUE;
		} else if(lhsObject.equals(Condition.FALSE) && rhsObject.equals(Condition.FALSE)){
			return Condition.FALSE;
		} else{
			throw new AttributeTypeException();
		}
	}

	@Override
	public Condition close(Store store, Set<String> CV) throws AttributeNotInStoreException, AttributeTypeException {
		Condition lhsObject = lhs.close(store, CV);
		Condition rhsObject = rhs.close(store, CV);
		if (lhsObject.equals(Condition.TRUE) || rhsObject.equals(Condition.TRUE)) {
			return Condition.TRUE;
		} else if(!lhsObject.equals(Condition.TRUE) && !rhsObject.equals(Condition.TRUE)){
			return new Or(lhsObject, rhsObject);
		} else{
			return Condition.FALSE;
		}
	}

	public static org.petitparser.parser.Parser parser(Parser basicCondition) {
		org.petitparser.parser.Parser parser =
				(basicCondition)
						.seq(CharacterParser.of('|').seq(CharacterParser.of('|').optional()).trim())
						.seq(basicCondition)
						.map((List<Object> values) -> {
							return new Or((Condition) values.get(0), (Condition) values.get(2));
						});

		return parser;
	}

	@Override
	public Condition relabel(Function<TypedVariable, Expression> relabelling) throws RelabellingTypeException {
		return new Or(this.lhs.relabel(relabelling), this.rhs.relabel(relabelling));
	}
}
