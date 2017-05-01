package de.metas.ui.web.window.model.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.adempiere.ad.expression.api.IExpressionEvaluator.OnVariableNotFound;
import org.adempiere.ad.expression.api.IStringExpression;
import org.adempiere.ad.expression.api.impl.CompositeStringExpression;
import org.adempiere.ad.security.IUserRolePermissions;
import org.adempiere.ad.security.UserRolePermissionsKey;
import org.adempiere.ad.security.impl.AccessSqlStringExpression;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.Check;
import org.compiere.util.Env;
import org.compiere.util.Evaluatee;
import org.compiere.util.Evaluatees;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import de.metas.ui.web.window.datatypes.DocumentId;
import de.metas.ui.web.window.descriptor.DocumentEntityDescriptor;
import de.metas.ui.web.window.descriptor.DocumentFieldWidgetType;
import de.metas.ui.web.window.descriptor.sql.SqlDocumentEntityDataBindingDescriptor;
import de.metas.ui.web.window.model.Document;
import de.metas.ui.web.window.model.DocumentQuery;
import de.metas.ui.web.window.model.DocumentQueryOrderBy;
import de.metas.ui.web.window.model.IDocumentFieldView;
import de.metas.ui.web.window.model.filters.DocumentFilter;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2016 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

public class SqlDocumentQueryBuilder
{

	public static SqlDocumentQueryBuilder newInstance(final DocumentEntityDescriptor entityDescriptor)
	{
		return new SqlDocumentQueryBuilder(entityDescriptor);
	}

	public static SqlDocumentQueryBuilder of(final DocumentQuery query)
	{
		return new SqlDocumentQueryBuilder(query.getEntityDescriptor())
				.setDocumentFilters(query.getFilters())
				.setParentDocument(query.getParentDocument())
				.setRecordId(query.getRecordId())
				//
				.noSorting(query.isNoSorting())
				.setOrderBys(query.getOrderBys())
				//
				.setPage(query.getFirstRow(), query.getPageLength())
				//
				;
	}

	private final Properties ctx;
	private final SqlDocumentEntityDataBindingDescriptor entityBinding;

	private transient Evaluatee _evaluationContext = null; // lazy
	private final List<DocumentFilter> documentFilters = new ArrayList<>();
	private Document parentDocument;
	private DocumentId recordId = null;

	private boolean noSorting = false;
	private List<DocumentQueryOrderBy> orderBys;

	private int firstRow;
	private int pageLength;

	//
	// Built values
	private IStringExpression _sqlWhereExpr;
	private List<Object> _sqlWhereParams;
	private IStringExpression _sqlExpr;
	private List<Object> _sqlParams;

	private SqlDocumentQueryBuilder(final DocumentEntityDescriptor entityDescriptor)
	{
		ctx = Env.getCtx();

		Check.assumeNotNull(entityDescriptor, "Parameter entityDescriptor is not null");
		entityBinding = SqlDocumentEntityDataBindingDescriptor.cast(entityDescriptor.getDataBinding());
		
	}

	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.omitNullValues()
				.add("TableName", entityBinding.getTableName())
				.toString();
	}

	public Evaluatee getEvaluationContext()
	{
		if (_evaluationContext == null)
		{
			_evaluationContext = createEvaluationContext();
		}
		return _evaluationContext;
	}

	private Evaluatee createEvaluationContext()
	{
		final Evaluatee evalCtx = Evaluatees.mapBuilder()
				.put(Env.CTXNAME_AD_Language, getAD_Language())
				.put(AccessSqlStringExpression.PARAM_UserRolePermissionsKey.getName(), getPermissionsKey())
				.build();

		final Evaluatee parentEvalCtx;
		if (parentDocument != null)
		{
			parentEvalCtx = parentDocument.asEvaluatee();
		}
		else
		{
			final Properties ctx = getCtx();
			final int windowNo = Env.WINDOW_MAIN; // TODO: get the proper windowNo
			final boolean onlyWindow = false;
			parentEvalCtx = Evaluatees.ofCtx(ctx, windowNo, onlyWindow);
		}

		return Evaluatees.compose(evalCtx, parentEvalCtx);
	}

	private Properties getCtx()
	{
		return ctx;
	}

	public String getAD_Language()
	{
		// TODO: introduce AD_Language as parameter
		return Env.getAD_Language(getCtx());
	}

	private String getPermissionsKey()
	{
		return UserRolePermissionsKey.toPermissionsKeyString(getCtx());
	}
	
	public IUserRolePermissions getPermissions()
	{
		return Env.getUserRolePermissions(getCtx());
	}

	public String getSql(final List<Object> outSqlParams)
	{
		final Evaluatee evalCtx = getEvaluationContext();
		final String sql = getSql().evaluate(evalCtx, OnVariableNotFound.Fail);
		final List<Object> sqlParams = getSqlParams();

		outSqlParams.addAll(sqlParams);
		return sql;
	}

	private IStringExpression getSql()
	{
		if (_sqlExpr == null)
		{
			buildSql();
		}
		return _sqlExpr;
	}

	public List<Object> getSqlParams()
	{
		return _sqlParams;
	}

	private final void buildSql()
	{
		final List<Object> sqlParams = new ArrayList<>();

		final CompositeStringExpression.Builder sqlBuilder = IStringExpression.composer();

		//
		// SELECT ... FROM ...
		sqlBuilder.append(getSqlSelectFrom());
		// NOTE: no need to add security here because it was already embedded in SqlSelectFrom

		//
		// WHERE
		final IStringExpression sqlWhereClause = getSqlWhere();
		if (!sqlWhereClause.isNullExpression())
		{
			sqlBuilder.append("\n WHERE ").append(sqlWhereClause);
			sqlParams.addAll(getSqlWhereParams());
		}

		//
		// ORDER BY
		if (isSorting())
		{
			final IStringExpression sqlOrderBy = getSqlOrderByEffective();
			if (!sqlOrderBy.isNullExpression())
			{
				sqlBuilder.append("\n ORDER BY ").append(sqlOrderBy);
			}
		}

		//
		// LIMIT/OFFSET
		final int firstRow = getFirstRow();
		if (firstRow > 0)
		{
			sqlBuilder.append("\n OFFSET ?");
			sqlParams.add(firstRow);
		}
		final int pageLength = getPageLength();
		if (pageLength > 0)
		{
			sqlBuilder.append("\n LIMIT ?");
			sqlParams.add(pageLength);
		}

		//
		//
		_sqlExpr = sqlBuilder.build();
		_sqlParams = sqlParams;
	}

	private IStringExpression getSqlSelectFrom()
	{
		return entityBinding.getSqlSelectAllFrom();
	}

	public IStringExpression getSqlWhere()
	{
		if (_sqlWhereExpr == null)
		{
			buildSqlWhereClause();
		}
		return _sqlWhereExpr;
	}

	public List<Object> getSqlWhereParams()
	{
		if (_sqlWhereParams == null)
		{
			buildSqlWhereClause();
		}
		return _sqlWhereParams;
	}

	private void buildSqlWhereClause()
	{
		final List<Object> sqlParams = new ArrayList<>();

		final CompositeStringExpression.Builder sqlWhereClauseBuilder = IStringExpression.composer();

		//
		// Entity's WHERE clause
		{
			final IStringExpression entityWhereClauseExpression = entityBinding.getSqlWhereClause();
			if (!entityWhereClauseExpression.isNullExpression())
			{
				sqlWhereClauseBuilder.appendIfNotEmpty("\n AND ");
				sqlWhereClauseBuilder.append(" /* entity where clause */ (").append(entityWhereClauseExpression).append(")");
			}
		}

		//
		// Key column
		final DocumentId recordId = getRecordId();
		if (recordId != null)
		{
			final String sqlKeyColumnName = entityBinding.getKeyColumnName();
			if (sqlKeyColumnName == null)
			{
				throw new AdempiereException("Failed building where clause because there is no Key Column defined in " + entityBinding);
			}

			sqlWhereClauseBuilder.appendIfNotEmpty("\n AND ");
			sqlWhereClauseBuilder.append(" /* key */ ").append(sqlKeyColumnName).append("=?");
			sqlParams.add(recordId.toInt());
		}

		//
		// Parent link where clause (if any)
		final Document parentDocument = getParentDocument();
		if (parentDocument != null)
		{
			final String parentLinkColumnName = entityBinding.getParentLinkColumnName();
			final String linkColumnName = entityBinding.getLinkColumnName();
			if (parentLinkColumnName != null && linkColumnName != null)
			{
				final IDocumentFieldView parentLinkField = parentDocument.getFieldView(parentLinkColumnName);
				final Object parentLinkValue = parentLinkField.getValue();
				final DocumentFieldWidgetType parentLinkWidgetType = parentLinkField.getWidgetType();

				final Class<?> targetClass = entityBinding.getFieldByFieldName(linkColumnName).getSqlValueClass();
				final Object sqlParentLinkValue = SqlDocumentsRepository.convertValueToPO(parentLinkValue, parentLinkColumnName, parentLinkWidgetType, targetClass);

				sqlWhereClauseBuilder.appendIfNotEmpty("\n AND ");
				sqlWhereClauseBuilder.append(" /* parent link */ ").append(linkColumnName).append("=?");
				sqlParams.add(sqlParentLinkValue);
			}
		}

		//
		// Document filters
		{
			final String sqlFilters = SqlDocumentFiltersBuilder.newInstance(entityBinding)
					.addFilters(getDocumentFilters())
					.buildSqlWhereClause(sqlParams);
			if(!Check.isEmpty(sqlFilters, true))
			{
				sqlWhereClauseBuilder.appendIfNotEmpty("\n AND ");
				sqlWhereClauseBuilder.append(" /* filters */ (\n").append(sqlFilters).append(")\n");
			}
		}

		//
		// Build the final SQL where clause
		_sqlWhereExpr = sqlWhereClauseBuilder.build();
		_sqlWhereParams = sqlParams;
	}

	public List<DocumentQueryOrderBy> getOrderBysEffective()
	{
		if (noSorting)
		{
			return ImmutableList.of();
		}

		final List<DocumentQueryOrderBy> queryOrderBys = getOrderBys();
		if (queryOrderBys != null && !queryOrderBys.isEmpty())
		{
			return queryOrderBys;
		}

		return entityBinding.getDefaultOrderBys();
	}

	public IStringExpression getSqlOrderByEffective()
	{
		final List<DocumentQueryOrderBy> orderBys = getOrderBysEffective();
		return SqlDocumentOrderByBuilder.newInstance(entityBinding::getFieldOrderBy).buildSqlOrderBy(orderBys);
	}

	public SqlDocumentEntityDataBindingDescriptor getEntityBinding()
	{
		return entityBinding;
	}

	public List<DocumentFilter> getDocumentFilters()
	{
		return documentFilters == null ? ImmutableList.of() : ImmutableList.copyOf(documentFilters);
	}

	public SqlDocumentQueryBuilder setDocumentFilters(final List<DocumentFilter> documentFilters)
	{
		this.documentFilters.clear();
		this.documentFilters.addAll(documentFilters);
		return this;
	}

	public SqlDocumentQueryBuilder addDocumentFilters(final List<DocumentFilter> documentFiltersToAdd)
	{
		this.documentFilters.addAll(documentFiltersToAdd);
		return this;
	}

	private Document getParentDocument()
	{
		return parentDocument;
	}

	public SqlDocumentQueryBuilder setParentDocument(final Document parentDocument)
	{
		this.parentDocument = parentDocument;
		return this;
	}

	public SqlDocumentQueryBuilder setRecordId(final DocumentId recordId)
	{
		this.recordId = recordId;
		return this;
	}

	private DocumentId getRecordId()
	{
		return recordId;
	}

	public SqlDocumentQueryBuilder noSorting()
	{
		noSorting = true;
		orderBys = null;
		return this;
	}
	
	public SqlDocumentQueryBuilder noSorting(final boolean noSorting)
	{
		this.noSorting = noSorting;
		if(noSorting)
		{
			orderBys = null;
		}
		return this;
	}

	public boolean isSorting()
	{
		return !noSorting;
	}

	public boolean isNoSorting()
	{
		return noSorting;
	}

	private List<DocumentQueryOrderBy> getOrderBys()
	{
		return orderBys;
	}

	public SqlDocumentQueryBuilder setOrderBys(final List<DocumentQueryOrderBy> orderBys)
	{
		// Don't throw exception if noSorting is true. Just do nothing.
		// REASON: it gives us better flexibility when this builder is handled by different methods, each of them adding stuff to it
		// Check.assume(!noSorting, "sorting enabled for {}", this);
		if (noSorting)
		{
			return this;
		}

		this.orderBys = orderBys;
		return this;
	}

	public SqlDocumentQueryBuilder setPage(final int firstRow, final int pageLength)
	{
		this.firstRow = firstRow;
		this.pageLength = pageLength;
		return this;
	}

	private int getFirstRow()
	{
		return firstRow;
	}

	private int getPageLength()
	{
		return pageLength;
	}
}
