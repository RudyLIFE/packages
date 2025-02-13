/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.providers.contacts;

import android.accounts.Account;
import android.text.TextUtils;

import com.google.common.base.Objects;
import com.mediatek.providers.contacts.SimCardUtils;

/**
 * Account information that includes the data set, if any.
 */
public class AccountWithDataSet {
    public static final AccountWithDataSet LOCAL = new AccountWithDataSet(null, null, null);

    private final String mAccountName;
    private final String mAccountType;
    private final String mDataSet;

    public AccountWithDataSet(String accountName, String accountType, String dataSet) {
        mAccountName = emptyToNull(accountName);
        mAccountType = emptyToNull(accountType);
        mDataSet = emptyToNull(dataSet);
    }

    private static final String emptyToNull(String text) {
        return TextUtils.isEmpty(text) ? null : text;
    }

    public static AccountWithDataSet get(String accountName, String accountType, String dataSet) {
        return new AccountWithDataSet(accountName, accountType, dataSet);
    }

    public static AccountWithDataSet get(Account account, String dataSet) {
        return new AccountWithDataSet(account.name, account.type, null);
    }

    public String getAccountName() {
        return mAccountName;
    }

    public String getAccountType() {
        return mAccountType;
    }

    public String getDataSet() {
        return mDataSet;
    }

    public boolean isLocalAccount() {
        /** M: Bug Fix for CR: ALPS00342992 
         * The fake accounts null account, local phone, SIM,
         * USIM, UIM accounts should be considered as the local account.
         * Original Code:
        return (mAccountName == null) && (mAccountType == null);
         * @{ */
        return (mAccountName == null) && (mAccountType == null)
                || ContactsProvider2.ACCOUNT_TYPE_LOCAL_PHONE.equals(mAccountType)
                || SimCardUtils.isSimAccount(mAccountType);
        /** @} */
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof AccountWithDataSet) {
            AccountWithDataSet other = (AccountWithDataSet) obj;
            return Objects.equal(mAccountName, other.getAccountName())
                    && Objects.equal(mAccountType, other.getAccountType())
                    && Objects.equal(mDataSet, other.getDataSet());
        }
        return false;
    }

    @Override
    public int hashCode() {
        int result = mAccountName != null ? mAccountName.hashCode() : 0;
        result = 31 * result + (mAccountType != null ? mAccountType.hashCode() : 0);
        result = 31 * result + (mDataSet != null ? mDataSet.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "AccountWithDataSet {name=" + mAccountName + ", type=" + mAccountType + ", dataSet="
                + mDataSet + "}";
    }

    /**
     * @return {@code true} if the owning {@link Account} is in the passed array.
     */
    public boolean inSystemAccounts(Account[] systemAccounts) {
        return inSystemAccounts(systemAccounts, false);
    }

    /**
     * M: modify for CTS case. ALPS01415185
     * @param systemAccounts
     * @param skip
     * @return true, if these accounts are system accounts.
     */
    public boolean inSystemAccounts(Account[] systemAccounts, boolean skip) {
        // Note we don't want to create a new Account object from this instance, as it may contain
        // null account name/type, which Account wouldn't accept.  So we need to compare field by
        // field.

        for (Account systemAccount : systemAccounts) {
            /// M: add for CTS test. ALPS01415185 @{
            if (skip && (Objects.equal(systemAccount.name, "cp removal acct 1")
                    || Objects.equal(systemAccount.name, "cp removal acct 2"))) {
                return true;
            }
            /// @}
            if (Objects.equal(systemAccount.name, getAccountName())
                    && Objects.equal(systemAccount.type, getAccountType())) {
                return true;
            }
        }
        return false;
    }
}
