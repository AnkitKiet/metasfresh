/**
 *
 */
package de.metas.adempiere.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.dao.IQueryOrderBy.Direction;
import org.adempiere.ad.dao.IQueryOrderBy.Nulls;
import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.service.IClientDAO;
import org.adempiere.util.proxy.Cached;
import org.compiere.model.I_AD_Client;
import org.compiere.model.I_AD_Language;
import org.compiere.model.I_AD_User_SaveCustomInfo;
import org.compiere.model.I_C_Country;
import org.compiere.model.I_C_Country_Sequence;
import org.compiere.model.I_C_Region;
import org.compiere.model.MCountry;
import org.compiere.util.Env;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import de.metas.adempiere.service.ICountryCustomInfo;
import de.metas.adempiere.service.ICountryDAO;
import de.metas.cache.CCache;
import de.metas.cache.annotation.CacheCtx;
import de.metas.i18n.ILanguageDAO;
import de.metas.i18n.ITranslatableString;
import de.metas.location.CountryId;
import de.metas.money.CurrencyId;
import de.metas.util.Check;
import de.metas.util.GuavaCollectors;
import de.metas.util.Services;
import lombok.NonNull;

/**
 * @author cg
 *
 */
public class CountryDAO implements ICountryDAO
{

	/** Country Cache */
	private final CCache<Integer, IndexedCountries> countriesCache = CCache.newCache(I_C_Country.Table_Name + "_AllIndexed", 1, CCache.EXPIREMINUTES_Never);
	/** C_Country_ID by AD_Client_ID */
	private final CCache<Integer, String> countryCodeByADClientId = CCache.<Integer, String> builder()
			.cacheName(I_C_Country.Table_Name + "#CountryCodeByAD_Client_ID")
			.tableName(I_C_Country.Table_Name)
			.initialCapacity(3)
			.additionalTableNameToResetFor(I_AD_Client.Table_Name)
			.build();

	private static final CountryId DEFAULT_C_Country_ID = CountryId.ofRepoId(101); // Germany

	private static final ImmutableBiMap<String, String> alpha2to3CountryCodes = buildAlpha2to3CountryCodes();

	private static final ImmutableBiMap<String, String> buildAlpha2to3CountryCodes()
	{
		final ImmutableBiMap.Builder<String, String> alpha2to3CountryCodesBuilder = ImmutableBiMap.builder();
		for (final String countryCodeAlpha2 : Locale.getISOCountries())
		{
			final Locale locale = new Locale("", countryCodeAlpha2);
			final String countryCodeAlpha3 = locale.getISO3Country();
			alpha2to3CountryCodesBuilder.put(countryCodeAlpha2, countryCodeAlpha3);
		}

		return alpha2to3CountryCodesBuilder.build();
	}

	@Override
	public ICountryCustomInfo retriveCountryCustomInfo(final Properties ctx, final String trxName)
	{
		final I_C_Country country = getDefault(ctx);

		final I_AD_User_SaveCustomInfo info = Services.get(IQueryBL.class)
				.createQueryBuilder(I_AD_User_SaveCustomInfo.class, Env.getCtx(), trxName)
				.addOnlyContextClient()
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_AD_User_SaveCustomInfo.COLUMNNAME_C_Country_ID, country.getC_Country_ID())
				.orderByDescending(I_AD_User_SaveCustomInfo.COLUMNNAME_Created)
				.create()
				.first();

		if (info != null)
		{
			return new CountryCustomInfoImpl(info.getCaptureSequence(), info.getC_Country_ID());
		}

		return null;
	}

	@Override
	public I_C_Country getDefault(final Properties ctx)
	{
		final IndexedCountries countries = getIndexedCountries();

		final String countryCode = getCountryCodeByADClientId(ctx);
		final I_C_Country country = countries.getByCountryCodeOrNull(countryCode);
		if (country != null)
		{
			return country;
		}

		// default
		return countries.getById(DEFAULT_C_Country_ID);
	}

	@Override
	public I_C_Country getById(@NonNull final CountryId id)
	{
		return getIndexedCountries().getByIdOrNull(id);
	}

	@Override
	public I_C_Country get(final Properties ctx_NOTNUSED, final int C_Country_ID)
	{
		return getById(CountryId.ofRepoId(C_Country_ID));
	}

	@Override
	public List<I_C_Country> getCountries(final Properties ctx)
	{
		final List<I_C_Country> countries = new ArrayList<>(getIndexedCountries().getAll());

		final Comparator<Object> cmp = new MCountry(ctx, 0, null);
		Collections.sort(countries, cmp);

		return countries;
	} // getCountries

	private IndexedCountries getIndexedCountries()
	{
		return countriesCache.getOrLoad(0, () -> retrieveIndexedCountries());
	}

	private static IndexedCountries retrieveIndexedCountries()
	{
		final List<I_C_Country> countries = Services.get(IQueryBL.class)
				.createQueryBuilder(I_C_Country.class)
				.addOnlyActiveRecordsFilter()
				.create()
				.list(I_C_Country.class);

		return new IndexedCountries(countries);
	}

	private String getCountryCodeByADClientId(final Properties ctx)
	{
		final int adClientId = Env.getAD_Client_ID(ctx);
		return countryCodeByADClientId.getOrLoad(adClientId, () -> retrieveCountryCodeIdByADClientId(adClientId));
	}

	private static final String retrieveCountryCodeIdByADClientId(final int adClientId)
	{
		final Properties ctx = Env.getCtx();
		final I_AD_Client client = Services.get(IClientDAO.class).retriveClient(ctx, adClientId);
		final I_AD_Language lang = Services.get(ILanguageDAO.class).retrieveByAD_Language(client.getAD_Language());
		return lang.getCountryCode();
	}

	@Override
	@Cached(cacheName = I_C_Region.Table_Name + "#by#C_Country_ID")
	public List<I_C_Region> retrieveRegions(@CacheCtx final Properties ctx, final int countryId)
	{
		return Services.get(IQueryBL.class)
				.createQueryBuilder(I_C_Region.class, ctx, ITrx.TRXNAME_None)
				.addEqualsFilter(I_C_Region.COLUMNNAME_C_Country_ID, countryId)
				//
				.orderBy()
				.addColumn(I_C_Region.COLUMNNAME_Name)
				.addColumn(I_C_Region.COLUMNNAME_C_Region_ID)
				.endOrderBy()
				//
				.create()
				.listImmutable(I_C_Region.class);

	}

	@Override
	public I_C_Country_Sequence retrieveCountrySequence(final I_C_Country country, final int adOrgId, final String adLanguage)
	{
		Check.assumeNotNull(country, "Parameter country is not null");

		final Properties ctx = InterfaceWrapperHelper.getCtx(country);
		return retrieveCountrySequences(ctx, country.getC_Country_ID())
				.stream()
				.filter(countrySequence -> countrySequenceMatches(countrySequence, adOrgId, adLanguage))
				.findFirst().orElse(null);
	}

	private static boolean countrySequenceMatches(final I_C_Country_Sequence sequence, final int adOrgId, final String adLanguage)
	{
		if (sequence.getAD_Org_ID() != adOrgId)
		{
			return false;
		}

		final String countrySequenceLanguage = sequence.getAD_Language();
		if (!Check.isEmpty(countrySequenceLanguage, true) && !countrySequenceLanguage.equals(adLanguage))
		{
			return false;
		}

		return true;
	}

	@Cached(cacheName = I_C_Country_Sequence.Table_Name + "#by#C_Country_ID")
	public List<I_C_Country_Sequence> retrieveCountrySequences(@CacheCtx final Properties ctx, final int countryId)
	{
		return Services.get(IQueryBL.class)
				.createQueryBuilder(I_C_Country_Sequence.class, ctx, ITrx.TRXNAME_None)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_C_Country_Sequence.COLUMN_C_Country_ID, countryId)
				//
				.orderBy()
				.addColumn(I_C_Country_Sequence.COLUMNNAME_AD_Language, Direction.Ascending, Nulls.Last)
				.endOrderBy()
				//
				.create()
				.listImmutable(I_C_Country_Sequence.class);
	}

	@Override
	public I_C_Country retrieveCountryByCountryCode(final String countryCode)
	{
		return getIndexedCountries().getByCountryCode(countryCode);
	}

	@Override
	public CountryId getCountryIdByCountryCode(final String countryCode)
	{
		return getIndexedCountries().getIdByCountryCode(countryCode);
	}

	@Override
	public String retrieveCountryCode2ByCountryId(@NonNull final CountryId countryId)
	{
		return getIndexedCountries().getById(countryId).getCountryCode();
	}

	@Override
	public String retrieveCountryCode3ByCountryId(@NonNull final CountryId countryId)
	{
		final String countryCode2 = retrieveCountryCode2ByCountryId(countryId);
		final String countryCode3 = alpha2to3CountryCodes.get(countryCode2);
		if (countryCode3 == null)
		{
			throw new AdempiereException("No country code alpha3 found for '" + countryCode2 + "'");
		}
		return countryCode3;
	}

	@Override
	public ITranslatableString getCountryNameById(final CountryId countryId)
	{
		final I_C_Country country = getIndexedCountries().getByIdOrNull(countryId);
		if (country == null)
		{
			return ITranslatableString.constant("<" + countryId + ">");
		}

		return InterfaceWrapperHelper.getModelTranslationMap(country)
				.getColumnTrl(I_C_Country.COLUMNNAME_Name, country.getName());
	}

	@Override
	public Optional<CurrencyId> getCountryCurrencyId(@NonNull final CountryId countryId)
	{
		final I_C_Country country = getById(countryId);
		return CurrencyId.optionalOfRepoId(country.getC_Currency_ID());
	}

	private static final class IndexedCountries
	{
		private final ImmutableList<I_C_Country> countries;
		private final ImmutableMap<CountryId, I_C_Country> countriesById;
		private final ImmutableMap<String, I_C_Country> countriesByCountryCode;

		private IndexedCountries(final List<I_C_Country> countries)
		{
			this.countries = ImmutableList.copyOf(countries);
			countriesById = Maps.uniqueIndex(countries, c -> CountryId.ofRepoId(c.getC_Country_ID()));
			countriesByCountryCode = countries.stream()
					.filter(country -> !Check.isEmpty(country.getCountryCode(), true)) // NOTE: in DB the CountryCode is mandatory but not in some unit tests
					.collect(GuavaCollectors.toImmutableMapByKey(I_C_Country::getCountryCode));
		}

		public List<I_C_Country> getAll()
		{
			return countries;
		}

		public I_C_Country getByIdOrNull(final CountryId countryId)
		{
			return countriesById.get(countryId);
		}

		public I_C_Country getById(final CountryId countryId)
		{
			final I_C_Country country = getByIdOrNull(countryId);
			if (country == null)
			{
				throw new AdempiereException("No country found for countryId=" + countryId);
			}
			return country;
		}

		public I_C_Country getByCountryCodeOrNull(final String countryCode)
		{
			return countriesByCountryCode.get(countryCode);
		}

		public I_C_Country getByCountryCode(final String countryCode)
		{
			final I_C_Country country = getByCountryCodeOrNull(countryCode);
			if (country == null)
			{
				throw new AdempiereException("No country found for countryCode=" + countryCode);
			}
			return country;
		}

		public CountryId getIdByCountryCode(final String countryCode)
		{
			final I_C_Country country = getByCountryCode(countryCode);
			return CountryId.ofRepoId(country.getC_Country_ID());
		}

	}
}
