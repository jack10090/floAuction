package com.flobi.floAuction;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.flobi.utility.functions;
import com.flobi.utility.items;

public class Auction {
	protected floAuction plugin;
	private String[] args;
	private String ownerName;
	private String scope;

	private long startingBid = 0;
	private long minBidIncrement = 0;
	private int quantity = 0;
	private int time = 0;
	private boolean active = false;
	
	private AuctionLot lot;
	private AuctionBid currentBid;
	
	// Scheduled timers:
	private int countdown = 0;
	private int countdownTimer = 0;
	
	public String getScope() {
		return scope;
	}
	
	public Auction(floAuction plugin, Player auctionOwner, String[] inputArgs, String scope) {
		ownerName = auctionOwner.getName();
		args = inputArgs;
		this.plugin = plugin; 
		this.scope = scope;

		// Remove the optional "start" arg:
		if (args.length > 0) {
			if (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("s")) {
				args = new String[inputArgs.length - 1];
				System.arraycopy(inputArgs, 1, args, 0, inputArgs.length - 1);
			}
		}
		
	}
	public Boolean start() {
		if (floAuction.useGoldStandard) {
			ItemStack typeStack = lot.getTypeStack();
			if (
					items.isSameItem(typeStack, new ItemStack(371)) ||
					items.isSameItem(typeStack, new ItemStack(266)) ||
					items.isSameItem(typeStack, new ItemStack(41))
			) {
				floAuction.sendMessage("auction-fail-gold-standard", ownerName, this);
				return false;
			}
		}
		if (!lot.AddItems(quantity, true)) {
			floAuction.sendMessage("auction-fail-insufficient-supply", ownerName, this);
			return false;
		}
		active = true;
		floAuction.sendMessage("auction-start", (CommandSender) null, this);
		
		// Set timer:
		final Auction thisAuction = this;
		countdown = time;
		
		countdownTimer = plugin.getServer().getScheduler().scheduleAsyncRepeatingTask(plugin, new Runnable() {
		    public void run() {
		    	thisAuction.countdown--;
		    	if (thisAuction.countdown == 0) {
		    		thisAuction.end(null);
		    		return;
		    	}
		    	if (thisAuction.countdown < 4) {
			    	floAuction.sendMessage("timer-countdown-notification", (CommandSender) null, thisAuction);
			    	return;
		    	}
		    	if (thisAuction.time >= 20) {
		    		if (thisAuction.countdown == (int) (thisAuction.time / 2)) {
				    	floAuction.sendMessage("timer-countdown-notification", (CommandSender) null, thisAuction);
		    		}
		    	}
		    }
		}, 20L, 20L);

		info(null);
		return true;
	}
	public void info(CommandSender sender) {
		if (!active) {
			floAuction.sendMessage("auction-info-no-auction", sender, this);
		} else if (currentBid == null) {
			floAuction.sendMessage("auction-info-header-nobids", sender, this);
			floAuction.sendMessage("auction-info-enchantment", sender, this);
			floAuction.sendMessage("auction-info-footer-nobids", sender, this);
		} else {
			floAuction.sendMessage("auction-info-header", sender, this);
			floAuction.sendMessage("auction-info-enchantment", sender, this);
			floAuction.sendMessage("auction-info-footer", sender, this);
		}
	}
	public void cancel(Player canceller) {
		floAuction.sendMessage("auction-cancel", (CommandSender) null, this);
		if (lot != null) lot.cancelLot();
		if (currentBid != null) currentBid.cancelBid();
		dispose();
	}
	public void end(Player ender) {
		if (currentBid == null || lot == null) {
			floAuction.sendMessage("auction-end-nobids", (CommandSender) null, this);
			if (lot != null) lot.cancelLot();
			if (currentBid != null) currentBid.cancelBid();
		} else {
			floAuction.sendMessage("auction-end", (CommandSender) null, this);
			lot.winLot(currentBid.getBidder().getName());
			currentBid.winBid();
		}
		dispose();
	}
	private void dispose() {
		plugin.getServer().getScheduler().cancelTask(countdownTimer);
		plugin.detachAuction(this);
	}
	public Boolean isValid() {
		if (!parseHeldItem()) return false;
		if (!parseArgs()) return false;
		if (!isValidOwner()) return false;
		if (!isValidAmount()) return false;
		if (!isValidStartingBid()) return false;
		if (!isValidIncrement()) return false;
		if (!isValidTime()) return false;
		return true;
	}
	public void Bid(Player bidder, String[] inputArgs) {
		AuctionBid bid = new AuctionBid(this, bidder, inputArgs);
		if (bid.getError() != null) {
			failBid(bid, bid.getError());
			return;
		}
		if (ownerName.equals(bidder.getName()) && !floAuction.allowBidOnOwn) {
			failBid(bid, "bid-fail-is-auction-owner");
			return;
		}
		if (currentBid == null) {
			setNewBid(bid, "bid-success-no-challenger");
			return;
		}
		long previousBidAmount = currentBid.getBidAmount();
		if (currentBid.getBidder().equals(bidder)) {
			if (bid.raiseOwnBid(currentBid)) {
				setNewBid(bid, "bid-success-update-own-bid");
			} else {
				if (bid.getMaxBidAmount() > currentBid.getMaxBidAmount()) {
					setNewBid(bid, "bid-success-update-own-maxbid");
				} else {
					failBid(bid, "bid-fail-already-current-bidder");
				}
			}
			return;
		}
		AuctionBid winner = null;
		AuctionBid looser = null;
		
		if (floAuction.useOldBidLogic) {
			if (bid.getMaxBidAmount() > currentBid.getMaxBidAmount()) {
				winner = bid;
				looser = currentBid;
			} else {
				winner = currentBid;
				looser = bid;
			}
			winner.raiseBid(Math.max(winner.getBidAmount(), Math.min(winner.getMaxBidAmount(), looser.getBidAmount() + minBidIncrement)));
		} else {
			// If you follow what this does, congratulations.  
			long baseBid = 0;
			if (bid.getBidAmount() >= currentBid.getBidAmount() + minBidIncrement) {
				baseBid = bid.getBidAmount();
			} else {
				baseBid = currentBid.getBidAmount() + minBidIncrement;
			}
			
			Integer prevSteps = (int) Math.floor((double)(currentBid.getMaxBidAmount() - baseBid + minBidIncrement) / minBidIncrement / 2);
			Integer newSteps = (int) Math.floor((double)(bid.getMaxBidAmount() - baseBid) / minBidIncrement / 2);

			if (newSteps >= prevSteps) {
				winner = bid;
				winner.raiseBid(baseBid + (Math.max(0, prevSteps) * minBidIncrement * 2));
				looser = currentBid;
			} else {
				winner = currentBid;
				winner.raiseBid(baseBid + (Math.max(0, newSteps + 1) * minBidIncrement * 2) - minBidIncrement);
				looser = bid;
			}
			
		}

		if (previousBidAmount <= winner.getBidAmount()) {
			// Did the new bid win?
			if (winner.equals(bid)) {
				setNewBid(bid, "bid-success-outbid");
			} else {
				// Did the old bid have to raise the bid to stay winner?
				if (previousBidAmount < winner.getBidAmount()) {
					floAuction.sendMessage("bid-auto-outbid", (CommandSender) null, this);
					failBid(bid, "bid-fail-auto-outbid");
				} else {
					floAuction.sendMessage("bid-fail-too-low", bid.getBidder(), this);
					failBid(bid, null);
				}
			}
		} else {
			// Seriously don't know what could cause this, but might as well take care of it.
			floAuction.sendMessage("bid-fail-too-low", bid.getBidder(), this);
		}
		
		
		
	}
	private void failBid(AuctionBid newBid, String reason) {
		newBid.cancelBid();
		floAuction.sendMessage(reason, newBid.getBidder(), this);
	}
	private void setNewBid(AuctionBid newBid, String reason) {
		if (currentBid != null) {
			currentBid.cancelBid();
		}
		currentBid = newBid;
		floAuction.sendMessage(reason, (CommandSender) null, this);
	}
	private Boolean parseHeldItem() {
		//TODO: Add check for damage and config option to check if can auction damaged items.
		Player owner = floAuction.server.getPlayer(ownerName);
		ItemStack heldItem = owner.getItemInHand();
		if (heldItem == null || heldItem.getAmount() == 0) {
			floAuction.sendMessage("auction-fail-hand-is-empty", owner, this);
			return false;
		}
		lot = new AuctionLot(heldItem, ownerName);
		return true;
	}
	private Boolean parseArgs() {
		// (amount) (starting price) (increment) (time)
		if (!parseArgAmount()) return false;
		if (!parseArgStartingBid()) return false;
		if (!parseArgIncrement()) return false;
		if (!parseArgTime()) return false;
		return true;
	}
	private Boolean isValidOwner() {
		if (ownerName == null) {
			floAuction.sendMessage("auction-fail-invalid-owner", (Player) plugin.getServer().getConsoleSender(), this);
			return false;
		}
		return true;
	}
	private Boolean isValidAmount() {
		if (quantity <= 0) {
			floAuction.sendMessage("auction-fail-quantity-too-low", ownerName, this);
			return false;
		}
		if (!functions.hasAmount(ownerName, quantity, lot.getTypeStack())) {
			floAuction.sendMessage("auction-fail-insufficient-supply", ownerName, this);
			return false;
		}
		return true;
	}
	private Boolean isValidStartingBid() {
		if (startingBid < 0) {
			floAuction.sendMessage("auction-fail-starting-bid-too-low", ownerName, this);
			return false;
		} else if (startingBid > floAuction.maxStartingBid) {
			floAuction.sendMessage("auction-fail-starting-bid-too-high", ownerName, this);
			return false;
		}
		return true;
	}
	private Boolean isValidIncrement() {
		if (getMinBidIncrement() < floAuction.minIncrement) {
			floAuction.sendMessage("auction-fail-increment-too-low", ownerName, this);
			return false;
		}
		if (getMinBidIncrement() > floAuction.maxIncrement) {
			floAuction.sendMessage("auction-fail-increment-too-high", ownerName, this);
			return false;
		}
		return true;
	}
	private Boolean isValidTime() {
		if (time < floAuction.minTime) {
			floAuction.sendMessage("auction-fail-time-too-low", ownerName, this);
			return false;
		}
		if (time > floAuction.maxTime) {
			floAuction.sendMessage("auction-fail-time-too-high", ownerName, this);
			return false;
		}
		return true;
	}
	private Boolean parseArgAmount() {
		ItemStack lotType = lot.getTypeStack();
		if (args.length > 0) {
			if (args[0].equalsIgnoreCase("this")) {
				quantity = lotType.getAmount();
			} else if (args[0].equalsIgnoreCase("all")) {
				quantity = functions.getAmount(ownerName, lotType);
			} else if (args[0].matches("[0-9]{1,7}")) {
				quantity = Integer.parseInt(args[0]);
			} else {
				plugin.getServer().broadcastMessage(args[0]);
				floAuction.sendMessage("parse-error-invalid-quantity", ownerName, this);
				return false;
			}
		} else {
			quantity = lotType.getAmount();
		}
		if (quantity < 0) {
			floAuction.sendMessage("parse-error-invalid-quantity", ownerName, this);
			return false;
		}
		return true;
	}
	private Boolean parseArgStartingBid() {
		if (args.length > 1) {
			if (args[1].matches("([0-9]{0,7}" + floAuction.decimalRegex + ")")) {
				startingBid = functions.getSafeMoney(Double.parseDouble(args[1]));
			} else {
				floAuction.sendMessage("parse-error-invalid-starting-bid", ownerName, this);
				return false;
			}
		} else {
			startingBid = floAuction.defaultStartingBid;
		}
		if (startingBid < 0) {
			floAuction.sendMessage("parse-error-invalid-starting-bid", ownerName, this);
			return false;
		}
		return true;
	}
	private Boolean parseArgIncrement() {
		if (args.length > 2) {
			if (args[2].matches("([0-9]{0,7}" + floAuction.decimalRegex + ")")) {
				minBidIncrement = functions.getSafeMoney(Double.parseDouble(args[2]));
			} else {
				floAuction.sendMessage("parse-error-invalid-bid-increment", ownerName, this);
				return false;
			}
		} else {
			minBidIncrement = floAuction.defaultBidIncrement;
		}
		if (minBidIncrement < 0) {
			floAuction.sendMessage("parse-error-invalid-bid-increment", ownerName, this);
			return false;
		}
		return true;
	}
	private Boolean parseArgTime() {
		if (args.length > 3) {
			if (args[3].matches("[0-9]{1,7}")) {
				time = Integer.parseInt(args[3]);
			} else {
				floAuction.sendMessage("parse-error-invalid-time", ownerName, this);
				return false;
			}
		} else {
			time = floAuction.defaultAuctionTime;
		}
		if (time < 0) {
			floAuction.sendMessage("parse-error-invalid-time", ownerName, this);
			return false;
		}
		return true;
	}
	public long getMinBidIncrement() {
		return minBidIncrement;
	}
	
	public ItemStack getLotType() {
		if (lot == null) {
			return null;
		}
		return lot.getTypeStack();
	}
	
	public int getLotQuantity() {
		if (lot == null) {
			return 0;
		}
		return lot.getQuantity();
	}
	public long getStartingBid() {
		return startingBid;
	}
	public AuctionBid getCurrentBid() {
		return currentBid;
	}
	public String getOwner() {
		return ownerName;
	}
	public int getRemainingTime() {
		return countdown;
	}
}
