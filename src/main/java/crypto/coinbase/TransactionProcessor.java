package crypto.coinbase;

import java.io.FileReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import crypto.coinbase.vo.CostBasisConfigProperties;
import crypto.coinbase.vo.FinalSale;
import crypto.coinbase.vo.Order;
import crypto.coinbase.vo.OrderType;
import crypto.coinbase.vo.TransactionType;
import crypto.coinbase.vo.Unit;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class TransactionProcessor {

	@Value("${input-file}")
	private String inputFile;

	private CoinDeskClient coinDeskClient;
	private GainLossCalculator gainLossCalculator;
	private CostBasisConfigProperties costBasisConfigProperties;

	public TransactionProcessor(CoinDeskClient coinDeskClient, GainLossCalculator gainLossCalculator,
			CostBasisConfigProperties costBasisConfigProperties) {
		this.coinDeskClient = coinDeskClient;
		this.gainLossCalculator = gainLossCalculator;
		this.costBasisConfigProperties = costBasisConfigProperties;
	}

	public void process() throws Exception {
		Map<String, Order> orderMap = new HashMap<>();
		Map<Unit, BigDecimal> balanceMap = new HashMap<>();
		Reader in = new FileReader(inputFile);
		Iterable<CSVRecord> records = CSVFormat.RFC4180.withFirstRecordAsHeader().parse(in);
		for (CSVRecord record : records) {
			BigDecimal amt = Utils.bigD(record.get("amount"));
			Unit unit = Unit.valueOf(record.get(5));
			TransactionType type = TransactionType.valueOf(record.get("type"));
			Date date = Utils.getDateYYYYMMDD(record.get("time").substring(0, 10));
			BigDecimal balance = Utils.bigD(record.get("balance"));
			if (!balanceMap.containsKey(unit) && !Unit.USD.equals(unit) && TransactionType.match.equals(type)) {
				balanceMap.put(unit, balance);
				BigDecimal priorBalance = balance.subtract(amt);
				if (priorBalance.compareTo(BigDecimal.ZERO) != 0) {
					Map<String, String> costBasisData = null;
					if (Unit.BTC.equals(unit)) {
						costBasisData = costBasisConfigProperties.getBtc();
					} else if (Unit.XRP.equals(unit)) {
						costBasisData = costBasisConfigProperties.getXrp();
					} else if (Unit.LTC.equals(unit)) {
						costBasisData = costBasisConfigProperties.getLtc();
					}

					log.info("Prior Cost Basis Data for {} = {}", unit.toString(), costBasisData);
					if (costBasisData == null || costBasisData.isEmpty()) {
						log.error("Unable to determine cost basis for {} with priorBalance: {}", unit, priorBalance);
						System.exit(0);
					}
					// Create Order to represent buy
					orderMap.put(unit.toString()+"-init", createInitialOrder(priorBalance, unit, costBasisData));
				}
			}
			if (TransactionType.withdrawal.equals(type) || TransactionType.deposit.equals(type)) {
				if (Unit.USD.equals(unit)) {
					// Moving USD not a reportable order
					continue;
				}
				String txnId = record.get("transfer id");
				Order order = new Order();
				order.setOrderType(TransactionType.withdrawal.equals(type) ? OrderType.SELL : OrderType.BUY);
				order.setUnit(unit);
				order.setCryptoAmount(amt.abs());
				order.setDate(date);
				order.setFee(BigDecimal.ZERO);
				// Fetch asset price to determine how much USD
				if (Unit.BTC.equals(unit)) {
					BigDecimal btcPrice = coinDeskClient.getBtcPrice(date);
					// BigDecimal btcPrice = bigD(9621.55);
					order.setUsdAmount(btcPrice.multiply(amt).abs());
				} else {
					// TODO
					order.setUsdAmount(BigDecimal.ZERO);
				}
				orderMap.put(txnId, order);
			} else {
				String txnId = record.get("order id");
				Order order = orderMap.get(txnId);
				if (order == null) {
					order = new Order();
					order.setDate(date);
					orderMap.put(txnId, order);
				}
				if (TransactionType.fee.equals(type)) {
					order.setFee(amt.abs());
				} else {
					if (Unit.USD.equals(unit)) {
						if (amt.doubleValue() < 0) {
							order.setOrderType(OrderType.BUY);
						} else {
							order.setOrderType(OrderType.SELL);
						}
						order.addUsd(amt.abs());
					} else {
						order.setUnit(unit);
						order.addCrypto(amt.abs());
					}
				}
			}
		}
		List<Order> orders = orderMap.values().stream().collect(Collectors.toList());
		Collections.sort(orders);
		log.info("Total Orders: {}", orders.size());
		Utils.createOrdersCSVFile(orders);
		List<FinalSale> sales = gainLossCalculator.calcGainLoss(orders);
		Utils.createFinalSaleCSVFile(sales);
	}

	private Order createInitialOrder(BigDecimal priorBalance, Unit unit, Map<String, String> costBasisData) throws ParseException {
		Order order = new Order();
		order.setCryptoAmount(priorBalance);
		order.setDate(Utils.getDateYYYYMMDD(costBasisData.get("date")));
		order.setFee(BigDecimal.ZERO);
		order.setOrderType(OrderType.BUY);
		order.setUnit(unit);
		order.setUsdAmount(Utils.bigD(costBasisData.get("price")));
		return order;
	}

}
