package io.antmedia.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import io.antmedia.AppSettings;
import io.antmedia.datastore.db.IDataStore;
import io.antmedia.datastore.db.types.Token;


public class TokenService implements ApplicationContextAware{

	public static final String BEAN_NAME = "token.service";
	protected static Logger logger = LoggerFactory.getLogger(TokenService.class);
	private AppSettings settings;
	private IDataStore dataStore;

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {

		dataStore = (IDataStore) applicationContext.getBean(IDataStore.BEAN_NAME);

		if (applicationContext.containsBean(AppSettings.BEAN_NAME)) {
			settings = (AppSettings)applicationContext.getBean(AppSettings.BEAN_NAME);

		}

	}

	public boolean checkToken(String tokenId) {
		boolean result = false;

		Token token = new Token();
		token.setTokenId(tokenId);
		if(dataStore.validateToken(token) != null) {
			result = true;	
		}	

		return result;
	}


}
