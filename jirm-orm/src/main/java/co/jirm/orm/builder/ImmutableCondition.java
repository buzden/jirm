package co.jirm.orm.builder;

import static com.google.common.base.Strings.nullToEmpty;

import co.jirm.core.util.JirmPrecondition;

import com.google.common.collect.ImmutableList;



public abstract class ImmutableCondition extends ImmutableParameterized<ImmutableCondition> implements Condition<ImmutableCondition> {
	
	protected final ConditionType conditionType;

	public abstract void accept(ConditionVisitor v);
	
	public static ImmutableCondition where(String custom) {
		if (nullToEmpty(custom).trim().isEmpty())
			return where();
		return new CustomCondition(custom);
	}
	
	public static ImmutableCondition where() {
		return new NoOp();
	}
	
	ImmutableCondition(ImmutableCondition child, Object value) {
		super(child, value);
		this.conditionType = child.conditionType;
	}

	ImmutableCondition(ImmutableCondition child, String key, Object value) {
		super(child, key, value);
		this.conditionType = child.conditionType;
	}

	ImmutableCondition(ConditionType type) {
		conditionType = type;
	}
	
	public ImmutableCondition and(ImmutableCondition other) {
		if (other.conditionType == ConditionType.NOOP)
			return this;
		if (isNoOp())
			return other;
		return new CombinedCondition(this, CombineType.AND, other);
	}
	public ImmutableCondition and(String sql) {
		if (isNoOp())
			return where(sql);
		return this.and(new CustomCondition(sql));

	}
    public ImmutableCondition or(ImmutableCondition other) {
		if (other.conditionType == ConditionType.NOOP)
			return this;
		if (isNoOp())
			return other;
		return this.or(other);
    }
    public ImmutableCondition or(String sql) {
		if (isNoOp())
			return where(sql);
		return new CombinedCondition(this, CombineType.OR, new CustomCondition(sql));
    }
    
    public ImmutableCondition not() {
    	if (isNoOp())
    		return this;
    	return new NotCondition(this);
    }
    
    public boolean isNoOp() {
    	return (this.conditionType == ConditionType.NOOP);
    }
    
    private final static class CombinedCondition extends ImmutableCondition {
    	
    	private final ImmutableList<ImmutableCondition> conditions;
    	
    	private CombinedCondition(ImmutableCondition lh, CombineType operator, ImmutableCondition rh) {
    		super(operator == CombineType.AND ? ConditionType.AND : ConditionType.OR);
    		if (this.conditionType == lh.conditionType && this.conditionType == rh.conditionType) {
    			this.conditions = ImmutableList.<ImmutableCondition>builder()
    					.addAll(((CombinedCondition)lh).conditions)
    					.addAll(((CombinedCondition)rh).conditions).build();
    		}
    		else if (this.conditionType == lh.conditionType && ! rh.conditionType.isCombine()) {
    			this.conditions = ImmutableList.<ImmutableCondition>builder()
    					.addAll(((CombinedCondition)lh).conditions).add(rh).build();
    		}
    		else if (this.conditionType == rh.conditionType && ! lh.conditionType.isCombine()) {
    			this.conditions = ImmutableList.<ImmutableCondition>builder()
    					.addAll(((CombinedCondition)rh).conditions).add(lh).build();
    		}
    		else {
    			this.conditions = ImmutableList.<ImmutableCondition>builder().add(lh).add(rh).build();
    		}
    	}
    	

    	@Override
    	public void accept(ConditionVisitor v) {
    		if (this.conditionType == ConditionType.AND)
    			v.visitAnd(conditions, this);
    		else
    			v.visitOr(conditions, this);
    			
    	}
    	
		private CombinedCondition(CombinedCondition child, Object value) {
			super(child, value);
			this.conditions = child.conditions;
		}

		private CombinedCondition(CombinedCondition child, String key, Object value) {
			super(child, key, value);
			this.conditions = child.conditions;
		}
		
		@Override
		public ImmutableCondition bind(String key, Object value) {
			return new CombinedCondition(this, key, value);
		}

		@Override
		public ImmutableCondition with(Object ... value ) {
			return new CombinedCondition(this, value[0]);
		}


		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + ((conditions == null) ? 0 : conditions.hashCode());
			return result;
		}


		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			CombinedCondition other = (CombinedCondition) obj;
			if (conditions == null) {
				if (other.conditions != null)
					return false;
			}
			else if (!conditions.equals(other.conditions))
				return false;
			return true;
		}
		
		

    }
    
    private final static class CustomCondition extends ImmutableCondition {
    	private final String sql;

    	@Override
    	public void accept(ConditionVisitor v) {
    		v.visitSql(sql, this);
    	}
    	
		private CustomCondition(String sql) {
			super(ConditionType.CUSTOM);
			this.sql = sql;
		}
		
		private CustomCondition(CustomCondition child, Object value) {
			super(child, value);
			this.sql = child.sql;
		}

		private CustomCondition(CustomCondition child, String key, Object value) {
			super(child, key, value);
			this.sql = child.sql;
		}
		
		@Override
		public ImmutableCondition bind(String key, Object value) {
			return new CustomCondition(this, key, value);
		}

		@Override
		public ImmutableCondition with(Object ... value) {
			//TODO check args
			return new CustomCondition(this, value[0]);
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + ((sql == null) ? 0 : sql.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			CustomCondition other = (CustomCondition) obj;
			if (sql == null) {
				if (other.sql != null)
					return false;
			}
			else if (!sql.equals(other.sql))
				return false;
			return true;
		}
    }

    private final static class NotCondition extends ImmutableCondition {
    	private final ImmutableCondition condition;
    	
    	@Override
    	public void accept(ConditionVisitor visitor) {
    		visitor.visitNot(condition);
    	}
    	
    	public NotCondition(ImmutableCondition condition) {
    		super(ConditionType.NOT);
    		this.condition = condition;
    	}
    	
		private NotCondition(NotCondition child, Object value) {
			super(child, value);
			this.condition = child.condition;
		}

		private NotCondition(NotCondition child, String key, Object value) {
			super(child, key, value);
			this.condition = child.condition;
		}
		
		@Override
		public NotCondition bind(String key, Object value) {
			return new NotCondition(this, key, value);
		}

		@Override
		public ImmutableCondition with(Object ... value) {
			return new NotCondition(this, value[0]);
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + ((condition == null) ? 0 : condition.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			NotCondition other = (NotCondition) obj;
			if (condition == null) {
				if (other.condition != null)
					return false;
			}
			else if (!condition.equals(other.condition))
				return false;
			return true;
		}
		
		
    }
    
    private final static class NoOp extends ImmutableCondition {
    	
		private NoOp() {
			super(ConditionType.NOOP);
		}
		
		@Override
		public void accept(ConditionVisitor v) {
			v.visitNoOp();
		}

		private NoOp(ImmutableCondition child, Object value) {
			super(child, value);
			throw JirmPrecondition.check.stateInvalid("Cannot set parameters on this condition.");
		}

		private NoOp(ImmutableCondition child, String key, Object value) {
			super(child, key, value);
			throw JirmPrecondition.check.stateInvalid("Cannot set parameters on this condition.");
		}

		@Override
		public ImmutableCondition bind(String key, Object value) {
			throw JirmPrecondition.check.stateInvalid("Cannot set parameters on this condition.");
		}

		@Override
		public ImmutableCondition with(Object ... value) {
			throw JirmPrecondition.check.stateInvalid("Cannot set parameters on this condition.");
		}
    	
    }
    
    public enum ConditionType {
    	CUSTOM, NOOP, AND, OR, NOT;
    	
    	public boolean isCombine() {
    		return this == AND || this == OR;
    	}
    }

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((conditionType == null) ? 0 : conditionType.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		ImmutableCondition other = (ImmutableCondition) obj;
		if (conditionType != other.conditionType)
			return false;
		return true;
	}
    
    
}