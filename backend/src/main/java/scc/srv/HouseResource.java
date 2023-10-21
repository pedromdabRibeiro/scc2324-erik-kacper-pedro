package scc.srv;

import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import redis.clients.jedis.Jedis;
import scc.cache.RedisCache;
import scc.data.RentalDAO;
import scc.data.house.AvailablePeriod;
import scc.data.house.HouseDAO;
import scc.db.CosmosDBLayer;
import scc.utils.Constants;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resource for accessing houses
 */ 
@Path("/house")
public class HouseResource
{
	/**
	 * Create a single house
	 * @param houseDAO the house to be created
	 * @return the id of the house
	 */
	@POST
	@Path("/")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response postHouse(HouseDAO houseDAO) {
		houseDAO.setId(UUID.randomUUID().toString());
		CosmosItemResponse<HouseDAO> response = CosmosDBLayer.getInstance().houseDB.upsertHouse(houseDAO);

		if (response.getStatusCode() == 201) {
			try {
				String id = response.getItem().getId();

				URI houseURL = new URI(Constants.getApplicationURL() + "/rest/house/" + id);
				return Response.created(houseURL).build();
			} catch (URISyntaxException e) {
				return Response.status(500).build();
			}
        }

		return Response.status(response.getStatusCode()).build();
	}

	/**
	 * Update a house by a given id
	 * @param id the id of the house to be updated
	 * @param houseDAO the updated content
	 * @return nothing - 2xx if update was successful
	 */
	@PUT
	@Path("/{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response putHouse(@PathParam("id") String id, HouseDAO houseDAO) {
		houseDAO.setId(id);
		CosmosItemResponse<HouseDAO> response = CosmosDBLayer.getInstance().houseDB.upsertHouse(houseDAO);

		return Response.status(response.getStatusCode()).build();
	}

	/**
	 * If house with given id exists, return house as JSON
	 * @param id of the house
	 * @return Response with house JSON for given id in body
	 */
	@GET
	@Path("/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getHouseByID(@PathParam("id") String id) {
		ObjectMapper mapper = new ObjectMapper();
		Jedis jedis = RedisCache.getCachePool().getResource();

		try {
			HouseDAO houseDAOcache = mapper.readValue(jedis.get("house:" + id), HouseDAO.class);
			jedis.close();
			return Response.accepted(houseDAOcache).build();
		} catch (JsonProcessingException e) {
			// Item not in cache
		}

		// Load house from database
		CosmosItemResponse<HouseDAO> responseHouse = CosmosDBLayer.getInstance().houseDB.getHouseByID(id);
		HouseDAO houseDAO = responseHouse.getItem();

		try {
			jedis.set("house:" + houseDAO.getId(), mapper.writeValueAsString(houseDAO));
		} catch (JsonProcessingException e) {
			
        }

        if (responseHouse.getStatusCode() < 300) {
			return Response.accepted(houseDAO).build();
		} else {
			return Response.noContent().build();
		}
	}

	/**
	 * Delete a house by a given id
	 * @param id of the house to be deleted
	 * @return nothing - 2xx if delete succeeded
	 */
	@DELETE
	@Path("/{id}")
	public Response deleteHouse(@PathParam("id") String id) {
		CosmosItemResponse<Object> response = CosmosDBLayer.getInstance().houseDB.deleteHouse(id);
		return Response.status(response.getStatusCode()).build();
	}

	/**
	 * Returns all houses for the given query Parameter.
	 * Valid Query parameters are:
	 * - userID
	 * - city
	 * - city & start-date & end-date
	 * @param userID of the owner of the house
	 * @param city of the house
	 * @param startDate of the period
	 * @param endDate of the period
	 * @return all houses for the given query parameters
	 */
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getHousesByQuery(@QueryParam("user-id") String userID,
									 @QueryParam("city") String city,
									 @QueryParam("start-date") String startDate,
									 @QueryParam("end-date") String endDate) {
		CosmosPagedIterable<HouseDAO> response;

		if (isValidQuery(userID)) { // List of houses of a given user
			response = CosmosDBLayer.getInstance().houseDB.getHousesByUserID(userID);
		} else if (isValidQuery(city) && isValidQuery(startDate) && isValidQuery(endDate)) { // Search of available houses for a given period and location
			response = CosmosDBLayer.getInstance().houseDB.getHousesByCityAndPeriod(city, startDate, endDate);
		} else if (isValidQuery(city)) { // List of available houses for a given location
			response = CosmosDBLayer.getInstance().houseDB.getHousesByCity(city);
		} else {
			return Response.status(400).build();
		}

		return Response.accepted(response.stream().toList()).build();
	}

	private boolean isValidQuery(String string) {
		return string != null && !string.trim().isEmpty();
	}

	/**
	 * Returns all houses which have soon a discount
	 * @return all houses which have soon a discount
	 */
	@GET
	@Path("/discounted-soon")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getDiscountedHousesNearFuture() {
		CosmosPagedIterable<HouseDAO> response = CosmosDBLayer.getInstance().houseDB.getDiscountedHousesNearFuture();

		return Response.accepted(response.stream().toList()).build();
	}

	/////////////////// RENTAL ENDPOINTS ///////////////////////

	@POST
	@Path("/{houseID}/rental")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response postRental(@PathParam("houseID") String houseID, RentalDAO rentalDAO) {
		String rentalID = UUID.randomUUID().toString();
		rentalDAO.setId(rentalID);
		rentalDAO.setHouseID(houseID);

		CosmosItemResponse<HouseDAO> responseHouse = CosmosDBLayer.getInstance().houseDB.getHouseByID(houseID);
		HouseDAO houseDAO = responseHouse.getItem();

		// TODO catch Exception
		LocalDate start = LocalDate.parse(rentalDAO.getStartDate(), Constants.dateFormat);
		LocalDate end = LocalDate.parse(rentalDAO.getEndDate(), Constants.dateFormat);

		// Check if there is an available period which contains the wanted rental period
		Optional<AvailablePeriod> period = houseDAO
				.getAvailablePeriods()
				.stream()
				.filter(p -> p.containsPeriod(start, end))
				.findFirst();

		if (period.isEmpty()) {
			return Response.noContent().build();
		}

		// Update house available periods
		Set<AvailablePeriod> newPeriods = period.get().subtract(start, end);
		houseDAO.getAvailablePeriods().remove(period.get());
		houseDAO.getAvailablePeriods().addAll(newPeriods);
		CosmosDBLayer.getInstance().houseDB.upsertHouse(houseDAO);

		// Compute price of the rental
		long daysBetween = start.until(end, ChronoUnit.DAYS);
		Float price = daysBetween * period.get().getNormalPricePerDay();
		rentalDAO.setPrice(price);

		CosmosItemResponse<RentalDAO> response = CosmosDBLayer.getInstance().rentalDB.upsertRental(rentalDAO);

		if (response.getStatusCode() == 201) {
			try {
				String path = "/rest/house/" + houseID + "/rental/" + rentalID;
				URI rentalURL = new URI(Constants.getApplicationURL() + path);
				return Response.created(rentalURL).build();
			} catch (URISyntaxException e) {
				return Response.status(500).build();
			}
		}

		return Response.status(response.getStatusCode()).build();
	}

	/**
	 * Update a rental by a given id
  	 * @param houseID the id of the house to which the rental belongs
	 * @param rentalID the id of the rental to be updated
	 * @param rentalDAO the updated content
	 * @return nothing - 2xx if update was successful
	 */
	@PUT
	@Path("/{houseID}/rental/{rentalID}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response putRental(@PathParam("houseID") String houseID, @PathParam("rentalID") String rentalID, RentalDAO rentalDAO) {
		rentalDAO.setId(rentalID);
		rentalDAO.setHouseID(houseID);
		CosmosItemResponse<RentalDAO> response = CosmosDBLayer.getInstance().rentalDB.upsertRental(rentalDAO);

		return Response.status(response.getStatusCode()).build();
	}

	/**
	 * If rental with given id exists, return rental as JSON
	 * @param houseID the id of the house to which the rental belongs
	 * @param rentalID the id of the rental to be fetched
	 * @return Response with rental JSON for given id in body
	 */
	@GET
	@Path("/{houseID}/rental/{rentalID}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getRentalByID(@PathParam("houseID") String houseID, @PathParam("rentalID") String rentalID) {
		CosmosItemResponse<RentalDAO> response = CosmosDBLayer.getInstance().rentalDB.getRentalByID(rentalID);

		return Response.accepted(response.getItem()).build();
	}

	/**
	 * Delete a rental by a given id
	 * @param rentalID of the rental to be deleted
	 * @return nothing - 2xx if delete succeeded
	 */
	@DELETE
	@Path("/{houseID}/rental/{rentalID}")
	public Response deleteRental(@PathParam("houseID") String houseID, @PathParam("rentalID") String rentalID) {
		CosmosItemResponse<Object> response = CosmosDBLayer.getInstance().rentalDB.deleteRental(rentalID);

		return Response.status(response.getStatusCode()).build();
	}
}
