package de.metas.banking.payment.impexp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.impexp.AbstractImportProcess;
import org.adempiere.impexp.IImportInterceptor;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.Services;
import org.adempiere.util.lang.IMutable;
import org.compiere.model.I_C_Payment;
import org.compiere.model.ModelValidationEngine;
import org.compiere.model.X_C_DocType;

import de.metas.banking.model.I_I_Datev_Payment;
import de.metas.banking.model.X_I_Datev_Payment;
import de.metas.document.DocTypeQuery;
import de.metas.document.IDocTypeDAO;
import de.metas.pricing.impexp.MDiscountSchemaImportTableSqlUpdater;
import lombok.NonNull;

/**
 * Import {@link I_I_Datev_Payment} to {@link I_C_Payment}.
 *
 */
public class DatevPaymentImportProcess extends AbstractImportProcess<I_I_Datev_Payment>
{
	@Override
	public Class<I_I_Datev_Payment> getImportModelClass()
	{
		return I_I_Datev_Payment.class;
	}

	@Override
	public String getImportTableName()
	{
		return I_I_Datev_Payment.Table_Name;
	}

	@Override
	protected String getTargetTableName()
	{
		return I_C_Payment.Table_Name;
	}

	@Override
	protected void updateAndValidateImportRecords()
	{
		MDiscountSchemaImportTableSqlUpdater.updateDiscountSchemaImportTable(getWhereClause());
	}

	@Override
	protected String getImportOrderBySql()
	{
		return I_I_Datev_Payment.COLUMNNAME_C_BPartner_ID;
	}

	@Override
	protected I_I_Datev_Payment retrieveImportRecord(final Properties ctx, final ResultSet rs) throws SQLException
	{
		return new X_I_Datev_Payment(ctx, rs, ITrx.TRXNAME_ThreadInherited);
	}

	@Override
	protected ImportRecordResult importRecord(@NonNull final IMutable<Object> state, @NonNull final I_I_Datev_Payment importRecord) throws Exception
	{
		return importDatevPayment(importRecord);
	}

	private ImportRecordResult importDatevPayment(@NonNull final I_I_Datev_Payment importRecord)
	{
		final ImportRecordResult schemaImportResult;

		final I_C_Payment payment;
		if (importRecord.getC_Payment_ID() <= 0)
		{
			payment = createNewPayment(importRecord);
			schemaImportResult = ImportRecordResult.Inserted;
		}
		else
		{
			payment = importRecord.getC_Payment();
			schemaImportResult = ImportRecordResult.Updated;
		}

		ModelValidationEngine.get().fireImportValidate(this, importRecord, payment, IImportInterceptor.TIMING_AFTER_IMPORT);
		InterfaceWrapperHelper.save(payment);

		importRecord.setC_Payment_ID(payment.getC_Payment_ID());
		InterfaceWrapperHelper.save(importRecord);

		return schemaImportResult;
	}

	private I_C_Payment createNewPayment(@NonNull final I_I_Datev_Payment importRecord)
	{
		final I_C_Payment payment;
		payment = InterfaceWrapperHelper.create(getCtx(), I_C_Payment.class, ITrx.TRXNAME_ThreadInherited);
		payment.setAD_Org_ID(importRecord.getAD_Org_ID());
		payment.setDescription("Import for debitorId/creditorId" + importRecord.getBPartnerValue());
		payment.setPayAmt(importRecord.getPayAmt());
		payment.setDiscountAmt(importRecord.getDiscountAmt());
		payment.setC_BPartner_ID(importRecord.getC_BPartner_ID());
		payment.setIsReceipt(importRecord.isReceipt());
		payment.setC_Invoice_ID(importRecord.getC_Invoice_ID());
		payment.setC_DocType_ID(extractC_DocType_ID(importRecord));
		InterfaceWrapperHelper.save(payment);
		return payment;
	}

	private int extractC_DocType_ID(@NonNull final I_I_Datev_Payment importRecord)
	{
		if (importRecord.isReceipt())
		{
		return Services.get(IDocTypeDAO.class).getDocTypeId(DocTypeQuery.builder()
				.docBaseType(X_C_DocType.DOCBASETYPE_ARReceipt)
				.adClientId(importRecord.getAD_Client_ID())
				.adOrgId(importRecord.getAD_Org_ID())
				.build());
		}
		else
		{
			return Services.get(IDocTypeDAO.class).getDocTypeId(DocTypeQuery.builder()
					.docBaseType(X_C_DocType.DOCBASETYPE_APPayment)
					.adClientId(importRecord.getAD_Client_ID())
					.adOrgId(importRecord.getAD_Org_ID())
					.build());
		}
	}

	@Override
	protected void markImported(@NonNull final I_I_Datev_Payment importRecord)
	{
		importRecord.setI_IsImported(X_I_Datev_Payment.I_ISIMPORTED_Imported);
		importRecord.setProcessed(true);
		InterfaceWrapperHelper.save(importRecord);
	}
}
