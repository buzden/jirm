/**
 * Copyright (C) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package co.jirm.orm.dao;

import static co.jirm.core.util.JirmPrecondition.check;
import static com.google.common.collect.Iterators.partition;
import static com.google.common.collect.Iterators.peekingIterator;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import co.jirm.core.builder.QueryForNumber;
import co.jirm.core.builder.TypedQueryFor;
import co.jirm.core.execute.SqlExecutor;
import co.jirm.core.util.ObjectMapUtils;
import co.jirm.core.util.ObjectMapUtils.NestedKeyValue;
import co.jirm.mapper.SqlObjectConfig;
import co.jirm.mapper.copy.CopyBuilder;
import co.jirm.mapper.definition.SqlObjectDefinition;
import co.jirm.mapper.definition.SqlParameterDefinition;
import co.jirm.orm.JirmFactory;
import co.jirm.orm.OrmConfig;
import co.jirm.orm.builder.delete.DeleteBuilderFactory;
import co.jirm.orm.builder.delete.DeleteRootClauseBuilder;
import co.jirm.orm.builder.select.SelectBuilderFactory;
import co.jirm.orm.builder.select.SelectRootClauseBuilder;
import co.jirm.orm.builder.update.UpdateBuilderFactory;
import co.jirm.orm.builder.update.UpdateObjectBuilder;
import co.jirm.orm.builder.update.UpdateRootClauseBuilder;
import co.jirm.orm.writer.SqlWriterStrategy;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.PeekingIterator;


public final class JirmDao<T> {

	private final SqlExecutor sqlExecutor;
	private final SqlObjectConfig config;
	private final SqlObjectDefinition<T> definition;
	private final SelectBuilderFactory<T> selectBuilderFactory;
	private final UpdateBuilderFactory<T> updateBuilderFactory;
	private final DeleteBuilderFactory<T> deleteBuilderFactory;
	private final SqlWriterStrategy writerStrategy;
    private final Optional<DaoHooks> daoHooks;
    private final Optional<JirmFactory> jirmFactory;
	
	private JirmDao(
			SqlExecutor sqlExecutor, 
			SqlObjectConfig config, 
			SqlObjectDefinition<T> definition,
			SqlWriterStrategy writerStrategy, 
			SelectBuilderFactory<T> selectBuilderFactory,
			UpdateBuilderFactory<T> updateBuilderFactory,
			DeleteBuilderFactory<T> deleteBuilderFactory,
			Optional<DaoHooks> daoHooks,
			Optional<JirmFactory> jirmFactory) {
		super();
		this.sqlExecutor = sqlExecutor;
		this.config = config;
		this.definition = definition;
		this.writerStrategy = writerStrategy;
		this.selectBuilderFactory = selectBuilderFactory;
		this.updateBuilderFactory = updateBuilderFactory;
		this.deleteBuilderFactory = deleteBuilderFactory;
        this.daoHooks = daoHooks;
        this.jirmFactory = jirmFactory;
	}

	public static <T> JirmDao<T> newInstance(Class<T> type, OrmConfig config) {
		return newInstance(type, config, Optional.<JirmFactory>absent());
	}

	public static <T> JirmDao<T> newInstance(Class<T> type, OrmConfig config, Optional<JirmFactory> jirmFactory) {
		SqlObjectDefinition<T> definition = config.getSqlObjectConfig().resolveObjectDefinition(type);
		SelectBuilderFactory<T> selectBuilderFactory = SelectBuilderFactory.newInstance(definition, config);
		UpdateBuilderFactory<T> updateBuilderFactory = UpdateBuilderFactory.newInstance(definition, config);
		DeleteBuilderFactory<T> deleteBuilderFactory = DeleteBuilderFactory.newInstance(definition, config);
		
		return new JirmDao<T>(
				config.getSqlExecutor(), 
				config.getSqlObjectConfig(), 
				definition, config.getSqlWriterStrategy(), 
				selectBuilderFactory, updateBuilderFactory, deleteBuilderFactory,
				config.getDaoHooks(),
				jirmFactory);
	}

    private static final JsonTypeInfo DEFAULT_TYPE_INFO = new JsonTypeInfo() {
        @Override
        public Id use() {
            return Id.CLASS;
        }

        @Override
        public As include() {
            return As.PROPERTY;
        }

        @Override
        public String property() {
            return "";
        }

        @Override
        public Class<?> defaultImpl() {
            return com.fasterxml.jackson.annotation.JsonTypeInfo.None.class;
        }

        @Override
        public boolean visible() {
            return false;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return JsonTypeInfo.class;
        }
    };
    private static final String DEFAULT_TYPE_INFO_PROPERTY = "@class";

	private LinkedHashMap<String, Object> toLinkedHashMap(T t, boolean bulkInsert, ForeignAct foreignAct) {
		LinkedHashMap<String, Object> m = config.getObjectMapper().convertObjectToSqlMap(t);
		/*
		 * Replace the complex objects with there ids.
		 */
		for(SqlParameterDefinition pd : definition.getManyToOneParameters().values()) {
			if (pd.getObjectDefinition().isPresent()
					&& pd.getObjectDefinition().get().getObjectDefintion().idParameter().isPresent()) {
                SqlParameterDefinition idDef = pd.getObjectDefinition().get().getObjectDefintion().idParameter().get();
				NestedKeyValue<Object> nkv =  ObjectMapUtils.getNestedKeyValue(m, pd.getParameterName(), idDef.getParameterName());
				if (nkv.isPresent()) {
					/*
					 * TODO: We only set it if the object is actually present. ie do you really want to set null?
					 */
                    final Class<?> actualClass;

                    // trying to manage the @JsonTypeInfo traces.
                    final JsonTypeInfo polyTypeInfo = pd.getParameterType().isAnnotationPresent(JsonTypeInfo.class) ?
                            pd.getParameterType().getAnnotation(JsonTypeInfo.class) :
                            DEFAULT_TYPE_INFO;
                    if (polyTypeInfo != null) {
                        if (polyTypeInfo.use() != JsonTypeInfo.Id.CLASS) {
                            // not supported yet.

                            throw new IllegalArgumentException("Json type info id " + polyTypeInfo.use() +
                                    " of the parameter " + pd.getParameterName() + " is not supported");
                        }

                        final String typeInfoProperty = "".equals(polyTypeInfo.property()) ? DEFAULT_TYPE_INFO_PROPERTY : polyTypeInfo.property();

                        final String actualClassName;
                        if (polyTypeInfo.include() == JsonTypeInfo.As.PROPERTY) {
                            final Object nestedObject = m.get(pd.getParameterName());
                            if (nestedObject instanceof Map) {
                                final NestedKeyValue<String> typeInfoNKV = ObjectMapUtils.getNestedKeyValue((Map) nestedObject, typeInfoProperty);

                                if (typeInfoNKV.isPresent()) {
                                    actualClassName = typeInfoNKV.object;
                                } else {
                                    actualClassName = null;
                                }
                            } else {
                                actualClassName = null;
                            }
                        } else if (polyTypeInfo.include() == JsonTypeInfo.As.EXTERNAL_PROPERTY) {
                            actualClassName = m.get(typeInfoProperty).toString();
                        } else {
                            // not supported yet.

                            throw new IllegalArgumentException("Json type info placement " + polyTypeInfo.include() +
                                    " of the parameter " + pd.getParameterName() + " is not supported");
                        }

                        if (actualClassName != null) {
                            Class<?> clazz = null;
                            try {
                                clazz = Thread.currentThread().getContextClassLoader().loadClass(actualClassName);
                            } catch (final ClassNotFoundException ignored) {
                            }

                            actualClass = clazz;
                        } else {
                            actualClass = null;
                        }
                    } else {
                        actualClass = null;
                    }

                    // acting with the formal (not actual) parameter type got from the parameter definition (pd var) if actual is not known.
                    final Class<?> parameterClass = actualClass != null ? actualClass : pd.getParameterType();

					actForeign(parameterClass, m.get(pd.getParameterName()), foreignAct);

                    // assuming the key (nkv.object) can be put as is.
					m.put(pd.getParameterName(), idDef != null ? idDef.convertToSql(nkv.object) : nkv.object);
				}
				else if (bulkInsert) {
					//TODO default annotation perhaps here?
					//http://stackoverflow.com/questions/197045/setting-default-values-for-columns-in-jpa
					m.put(pd.getParameterName(), null);
				}
			}
		}
		for(Entry<String,Object> e : m.entrySet()) {
			Optional<SqlParameterDefinition> d = definition.resolveParameter(e.getKey());
			if (d.isPresent()) {
				e.setValue(d.get().convertToSql(e.getValue()));
			}
		}
		if (bulkInsert) {
			LinkedHashMap<String, Object> copy = new LinkedHashMap<String, Object>(definition.getIdParameters().size());
			/*
			 * Order and the number of parameters is really important for bulk insert.
			 */
			for(SqlParameterDefinition pd : definition.getParameters().values()) {
				check.state(m.containsKey(pd.getParameterName()), 
						"Missing parameter for bulk insert: {}", pd.getParameterName());
				Object o = m.get(pd.getParameterName());
				copy.put(pd.getParameterName(), o);
			}
			m = copy;
		}
		return m;
				
	}

	private void actForeign(final Class<?> clazz, final Object object, ForeignAct foreignAct) {
		if (object != null && jirmFactory.isPresent()) {
			@SuppressWarnings("unchecked")
			final JirmDao<Object> foreignDao = ((JirmDao<Object>) jirmFactory.get().daoFor(clazz));

			foreignAct.act(foreignDao, object);
		}
	}

	private static interface ForeignAct {
		public <T> void act(final JirmDao<T> dao, final T object);

		public static final ForeignAct NO_ACT = new ForeignAct() {
			@Override
			public <T> void act(final JirmDao<T> dao, final T object) {
			}
		};
	}

	private static class ForeignInsert implements ForeignAct {
		public static final ForeignInsert INSERT = new ForeignInsert();

		@Override
		public <T> void act(final JirmDao<T> dao, final T object) {
			dao.insert(object);
		}
	}

	public CopyBuilder<T> copyBuilder() {
		return CopyBuilder.newInstance(definition.getObjectType(), config.getObjectMapper());
	}
	
	protected SqlParameterDefinition idParameter() {
		check.state(definition.idParameter().isPresent(), "No id parameter for : {}", 
				definition.getObjectType());
		return this.definition.idParameter().get();
	}
	
	public SelectRootClauseBuilder<? extends TypedQueryFor<T>> select() {
		return selectBuilderFactory.select();
	}
	
	public SelectRootClauseBuilder<? extends QueryForNumber> count() {
		return selectBuilderFactory.count();
	}
	
	public UpdateRootClauseBuilder<Integer> update() {
		return updateBuilderFactory.update();
	}
	
	public DeleteRootClauseBuilder<Integer> delete() {
		return deleteBuilderFactory.delete();
	}
	
	public Optional<T> findOptionalById(Object id) {
		return select().where()
				.property(idParameter().getParameterName()).eq(id)
				.query()
				.forOptional();
	}
	
	public T findById(Object id) {
		return select().where()
				.property(idParameter().getParameterName()).eq(id)
				.query()
				.forObject();
	}
	
	public void insert(T t) {
		LinkedHashMap<String, Object> m = toLinkedHashMap(t, false, ForeignInsert.INSERT);
		Iterator<Entry<String, Object>> it = m.entrySet().iterator();
		/*
		 * Remove the null values that are to be generated.
		 */
		while(it.hasNext()) {
			Entry<String, Object> e = it.next();
			Optional<SqlParameterDefinition> p = definition.resolveParameter(e.getKey());
			if (p.isPresent() && p.get().isGenerated() && e.getValue() == null) {
				it.remove();
			}
			else if (p.isPresent() && p.get().isVersion() && e.getValue() == null) {
				e.setValue(0);
			}
		}
		insert(m);
	}
	
	public int deleteById(Object id) {
		return deleteBuilderFactory
			.delete()
			.where().property(idParameter().getParameterName()).eq(id)
			.execute();
	}
	
	public UpdateObjectBuilder<T> update(T t) {
		// todo to probably change the foreign act (probably with the ForeignAct interface).
		LinkedHashMap<String, Object> m = toLinkedHashMap(t, false, ForeignAct.NO_ACT);
		return updateBuilderFactory.update(m);
	}

	public T reload(T t) {
        // todo to probably change the foreign act (probably with the ForeignAct interface).
		LinkedHashMap<String, Object> m = toLinkedHashMap(t, false, ForeignAct.NO_ACT);
		Optional<SqlParameterDefinition> id = definition.idParameter();
		check.state(id.isPresent(), "No id definition");
		Optional<Object> o = id.get().valueFrom(m);
		return findById(o.get());
	}
	
	public void insert(Map<String,Object> values) {
		if (daoHooks.isPresent()) {
			daoHooks.get().beforeInsert(definition, values);
		}

		StringBuilder qb = new StringBuilder();
		writerStrategy.insertStatement(qb, definition, values);
		sqlExecutor.update(qb.toString(), writerStrategy.fillValues(definition, values).toArray());
	}
	
	public void insert(Iterator<T> values, int batchSize) {
		Iterator<Map<String,Object>> t = Iterators.transform(values, new Function<T, Map<String,Object>>() {
			@Override
			public Map<String, Object> apply(T input) {
				return toLinkedHashMap(input, true, ForeignInsert.INSERT);
			}
		});
		insertMaps(t, batchSize);
	}
	
	public void insertMaps(Iterator<Map<String,Object>> values, int batchSize) {
		if (! values.hasNext() ) return;
		PeekingIterator<Map<String,Object>> vs = peekingIterator(values);
		Map<String,Object> first = vs.peek();
		final String sql = writerStrategy.insertStatement(new StringBuilder(), definition, first).toString();
		ImmutableList<String> keys = ImmutableList.copyOf(vs.peek().keySet());
		Iterator<List<Map<String,Object>>> it = partition(vs, batchSize);

		while (it.hasNext()) {
			List<Map<String,Object>> batch = it.next();
			final List<Object[]> batchValues = Lists.newArrayListWithExpectedSize(batch.size());
			for (Map<String,Object> b : batch) {
				ImmutableList<String> actualKeys = ImmutableList.copyOf(b.keySet());
				check.state(actualKeys.equals(keys), "Keys don't match up to {} for {}", keys, actualKeys);
				batchValues.add(writerStrategy.fillValues(definition, b).toArray());
			}
			/*
			 * TODO this will keep making a prepared statementS.
			 * Hopefully the JDBC driver has some caching for this.
			 */
			sqlExecutor.batchUpdate(sql, batchValues);
		}
		
	}
	
	
	public SelectBuilderFactory<T> getSelectBuilderFactory() {
		return selectBuilderFactory;
	}
	
	public UpdateBuilderFactory<T> getUpdateBuilderFactory() {
		return updateBuilderFactory;
	}
}
