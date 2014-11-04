package com.coinomi.wallet.ui;

/*
 * Copyright 2011-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.coinomi.core.coins.CoinType;
import com.coinomi.core.wallet.WalletPocket;
import com.coinomi.wallet.Constants;
import com.coinomi.wallet.R;
import com.coinomi.wallet.util.Fonts;
import com.coinomi.wallet.util.GenericUtils;
import com.coinomi.wallet.util.WalletUtils;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.Coin;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionConfidence;
import com.google.bitcoin.core.TransactionConfidence.ConfidenceType;

/**
 * @author Andreas Schildbach
 */
public class TransactionsListAdapter extends BaseAdapter {
    private final Context context;
    private final LayoutInflater inflater;
    private final WalletPocket walletPocket;

    private final List<Transaction> transactions = new ArrayList<Transaction>();
    private int precision = 0;
    private int shift = 0;
    private boolean showEmptyText = false;

    private final int colorSignificant;
    private final int colorInsignificant;
    private final int colorError;
    private final int colorCircularBuilding = Color.parseColor("#44ff44");
    private final String textCoinBase;

    private final Map<String, String> labelCache = new HashMap<String, String>();
    private final static String CACHE_NULL_MARKER = "";

    private static final String CONFIDENCE_SYMBOL_DEAD = "\u271D"; // latin cross
    private static final String CONFIDENCE_SYMBOL_UNKNOWN = "?";

    private static final int VIEW_TYPE_TRANSACTION = 0;

    public TransactionsListAdapter(final Context context, @Nonnull final WalletPocket walletPocket) {
        this.context = context;
        inflater = LayoutInflater.from(context);

        this.walletPocket = walletPocket;

        final Resources resources = context.getResources();
        colorSignificant = resources.getColor(R.color.gray_87_text);
        colorInsignificant = resources.getColor(R.color.gray_26_hint_text);
        colorError = resources.getColor(R.color.fg_error);
        textCoinBase = context.getString(R.string.wallet_transactions_coinbase);
    }

    public void setPrecision(final int precision, final int shift) {
        this.precision = precision;
        this.shift = shift;

        notifyDataSetChanged();
    }

    public void clear()
    {
        transactions.clear();

        notifyDataSetChanged();
    }

    public void replace(@Nonnull final Transaction tx)
    {
        transactions.clear();
        transactions.add(tx);

        notifyDataSetChanged();
    }

    public void replace(@Nonnull final Collection<Transaction> transactions)
    {
        this.transactions.clear();
        this.transactions.addAll(transactions);

        showEmptyText = true;

        notifyDataSetChanged();
    }

    @Override
    public boolean isEmpty()
    {
        return showEmptyText && super.isEmpty();
    }

    @Override
    public int getCount() {
        return transactions.size();
    }

    @Override
    public Transaction getItem(final int position) {
        if (position == transactions.size())
            return null;

        return transactions.get(position);
    }

    @Override
    public long getItemId(final int position) {
        if (position == transactions.size())
            return 0;

        return WalletUtils.longHash(transactions.get(position).getHash());
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View getView(final int position, View row, final ViewGroup parent) {
        final int type = getItemViewType(position);

        if (type == VIEW_TYPE_TRANSACTION) {
            if (row == null)
                row = inflater.inflate(R.layout.transaction_row, null);

            final Transaction tx = getItem(position);
            bindView(row, tx);
        } else {
            throw new IllegalStateException("unknown type: " + type);
        }

        return row;
    }

    public void bindView(@Nonnull final View row, @Nonnull final Transaction tx) {
        final TransactionConfidence confidence = tx.getConfidence();
        final ConfidenceType confidenceType = confidence.getConfidenceType();
        final boolean isOwn = confidence.getSource().equals(TransactionConfidence.Source.SELF);
        final boolean isCoinBase = tx.isCoinBase();
//        final boolean isInternal = WalletUtils.isInternal(tx);

        final Coin value = tx.getValue(walletPocket);
        final boolean sent = value.signum() < 0;

        final CoinType type = walletPocket.getCoinType();

        final TextView rowDirectionText = (TextView) row.findViewById(R.id.transaction_row_direction_text);
        final TextView rowDirectionFontIcon = (TextView) row.findViewById(R.id.transaction_row_direction_font_icon);
        Fonts.setTypeface(rowDirectionFontIcon, Fonts.Font.ENTYPO_COINOMI);
        // TODO implement date
//        final TextView rowDate = (TextView) row.findViewById(R.id.transaction_row_time);
        final TextView rowLabel = (TextView) row.findViewById(R.id.transaction_row_label);
        final CurrencyTextView rowValue = (CurrencyTextView) row.findViewById(R.id.transaction_row_value);

        // confidence
        if (confidenceType == ConfidenceType.PENDING) {
            rowLabel.setTextColor(colorInsignificant);
            rowValue.setTextColor(colorInsignificant);
            rowDirectionText.setTextColor(colorInsignificant);
            rowDirectionFontIcon.setTextColor(colorInsignificant);
            rowDirectionFontIcon.setBackgroundResource(R.drawable.transaction_row_cyrcle_bg_pending);
        } else if (confidenceType == ConfidenceType.BUILDING) {
            rowLabel.setTextColor(colorSignificant);
            rowValue.setTextColor(colorSignificant);
            rowDirectionText.setTextColor(colorSignificant);
            rowDirectionFontIcon.setTextColor(colorSignificant);
            if (value.isNegative()) {
                rowDirectionFontIcon.setBackgroundResource(R.drawable.transaction_row_cyrcle_bg_send);
                rowValue.setTextColor(context.getResources().getColor(R.color.send_color_fg));
            } else {
                rowDirectionFontIcon.setBackgroundResource(R.drawable.transaction_row_cyrcle_bg_receive);
                rowValue.setTextColor(context.getResources().getColor(R.color.receive_color_fg));
            }
        } else if (confidenceType == ConfidenceType.DEAD) {
            rowLabel.setTextColor(colorSignificant);
            rowValue.setTextColor(colorSignificant);
            Fonts.strikeThrough(rowLabel);
            Fonts.strikeThrough(rowValue);
        } else {
            rowDirectionText.setTextColor(colorError);
            rowLabel.setTextColor(colorInsignificant);
            rowValue.setTextColor(colorInsignificant);
            rowDirectionFontIcon.setTextColor(colorInsignificant);
            rowDirectionFontIcon.setBackgroundResource(R.drawable.transaction_row_cyrcle_bg_pending);
        }

        // Money direction
        if (value.isNegative()) {
            rowDirectionText.setText(context.getResources().getString(R.string.sent_to));
            rowDirectionFontIcon.setText(context.getResources().getString(R.string.font_icon_send));
        } else {
            rowDirectionText.setText(context.getResources().getString(R.string.received_with));
            rowDirectionFontIcon.setText(context.getResources().getString(R.string.font_icon_receive));
        }

        // date
//        final Date time = tx.getUpdateTime();
//        rowDate.setText(time != null ? (DateUtils.getRelativeTimeSpanString(context, time.getTime())) : null);

        // coinbase TODO
//        final View rowCoinbase = row.findViewById(R.id.transaction_row_coinbase);
//        rowCoinbase.setVisibility(isCoinBase ? View.VISIBLE : View.GONE);

        // address - label
        final Address address = sent ?
                WalletUtils.getSendToAddress(tx, walletPocket) : // we send payment to this address
                WalletUtils.getReceivedWithAddress(tx, walletPocket); // received with this address
        final String label;
        if (isCoinBase)
            label = textCoinBase;
//        else if (isInternal)
//            label = textInternal;
        else if (address != null)
            label = resolveLabel(address.toString());
        else
            label = "?";

        if (label != null) {
            rowLabel.setText(label);
        } else {
            rowLabel.setText(GenericUtils.addressSplitToGroups(address.toString()));
        }
        rowLabel.setTypeface(label != null ? Typeface.DEFAULT : Typeface.MONOSPACE);

        // value
        rowValue.setAlwaysSigned(true);
        rowValue.setPrecision(precision, shift);//TODO make configurable
        rowValue.setAmount(value);
    }

    private String resolveLabel(@Nonnull final String address) {
        return null;
//        final String cachedLabel = labelCache.get(address);
//        if (cachedLabel == null)
//        {
//            final String label = AddressBookProvider.resolveLabel(context, address);
//            if (label != null)
//                labelCache.put(address, label);
//            else
//                labelCache.put(address, CACHE_NULL_MARKER);
//            return label;
//        }
//        else
//        {
//            return cachedLabel != CACHE_NULL_MARKER ? cachedLabel : null;
//        }
    }

    public void clearLabelCache()
    {
        labelCache.clear();

        notifyDataSetChanged();
    }
}
